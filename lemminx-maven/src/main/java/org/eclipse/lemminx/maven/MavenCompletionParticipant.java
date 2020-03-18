/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.Maven;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.eclipse.lemminx.maven.searcher.LocalRepositorySearcher;
import org.eclipse.lemminx.maven.searcher.LocalRepositorySearcher.GroupIdArtifactId;
import org.eclipse.lemminx.maven.searcher.RemoteRepositoryIndexSearcher;
import org.eclipse.lemminx.maven.snippets.SnippetRegistry;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4xml.commons.BadLocationException;
import org.eclipse.lsp4xml.commons.TextDocument;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMElement;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.services.extensions.CompletionParticipantAdapter;
import org.eclipse.lsp4xml.services.extensions.ICompletionRequest;
import org.eclipse.lsp4xml.services.extensions.ICompletionResponse;
import org.eclipse.lsp4xml.utils.XMLPositionUtility;

public class MavenCompletionParticipant extends CompletionParticipantAdapter {

	static interface InsertionStrategy {
		/**
		 * set current element value and add siblings as addition textEdits
		 */
		public static final InsertionStrategy ELEMENT_VALUE_AND_SIBLING = new InsertionStrategy() {};

		/**
		 * insert elements as children of the parent element
		 */
		public static final InsertionStrategy CHILDREN_ELEMENTS = new InsertionStrategy() {};
		
		public static final class NodeWithChildrenInsertionStrategy implements InsertionStrategy {
			public final String elementName;
			
			public NodeWithChildrenInsertionStrategy(String elementName) {
				this.elementName = elementName;
			}
		}
	}

	private boolean snippetsLoaded;
	private final LocalRepositorySearcher localRepositorySearcher = new LocalRepositorySearcher(RepositorySystem.defaultUserLocalRepository);
	private final MavenProjectCache cache;
	private final RemoteRepositoryIndexSearcher indexSearcher;
	private MavenPluginManager pluginManager;

	public MavenCompletionParticipant(MavenProjectCache cache, RemoteRepositoryIndexSearcher indexSearcher, MavenPluginManager pluginManager) {
		this.cache = cache;
		this.indexSearcher = indexSearcher;
		this.pluginManager = pluginManager;
	}
	
	@Override
	public void onTagOpen(ICompletionRequest request, ICompletionResponse response)
			throws Exception {
		if ("configuration".equals(request.getParentElement().getLocalName())) {
			MavenPluginUtils.collectPluginConfigurationParameters(request, cache, pluginManager).stream()
					.map(parameter -> toTag(parameter.getName(), MavenPluginUtils.getMarkupDescription(parameter), request))
					.forEach(response::addCompletionItem);
		}
	}

	@Override
	public void onXMLContent(ICompletionRequest request, ICompletionResponse response) throws Exception {
		if (request.getXMLDocument().getText().length() < 2) {
			response.addCompletionItem(createMinimalPOMCompletionSnippet(request));
		}
		DOMElement parent = request.getParentElement();
		if (parent == null || parent.getLocalName() == null) {
			return;
		}
		DOMElement grandParent = parent.getParentElement();
		boolean isPlugin = "plugin".equals(parent.getLocalName()) || (grandParent != null && "plugin".equals(grandParent.getLocalName()));
		boolean isParentDeclaration = "parent".equals(parent.getLocalName()) || (grandParent != null && "parent".equals(grandParent.getLocalName()));
		Optional<String> groupId = grandParent == null ? Optional.empty() : grandParent.getChildren().stream()
				.filter(DOMNode::isElement)
				.filter(node -> "groupId".equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream())
				.map(DOMNode::getTextContent)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.findFirst();
		Optional<String> artifactId = grandParent == null ? Optional.empty() : grandParent.getChildren().stream()
				.filter(DOMNode::isElement)
				.filter(node -> "artifactId".equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream())
				.map(DOMNode::getTextContent)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.findFirst();
		InsertionStrategy gavInsertionStrategy = computeGAVInsertionStrategy(request);
		switch (parent.getLocalName()) {
		case "scope":
			collectSimpleCompletionItems(Arrays.asList(DependencyScope.values()), DependencyScope::getName,
					DependencyScope::getDescription, request).forEach(response::addCompletionAttribute);
			break;
		case "phase":
			collectSimpleCompletionItems(Arrays.asList(Phase.ALL_STANDARD_PHASES), phase -> phase.id,
					phase -> phase.description, request).forEach(response::addCompletionAttribute);
			break;
		case "groupId":
			if (isParentDeclaration) {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(mavenProject -> toGAVCompletionItem(new GroupIdArtifactId(mavenProject.getGroupId(), mavenProject.getArtifactId()), new DefaultArtifactVersion(mavenProject.getVersion()), mavenProject.getDescription(), request, null)).ifPresent(response::addCompletionItem);
				}
				// TODO localRepo
				// TODO remoteRepos
			} else {
				// TODO if artifactId is set and match existing content, suggest only matching groupId
				collectSimpleCompletionItems(isPlugin ? localRepositorySearcher.searchPluginGroupIds() : localRepositorySearcher.searchGroupIds(),
						Function.identity(), Function.identity(), request).forEach(response::addCompletionAttribute);
				internalCollectRemoteGAVCompletion(request, isPlugin, gavInsertionStrategy).forEach(response::addCompletionItem);
			}
			break;
		case "artifactId":
			if (isParentDeclaration) {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(mavenProject -> toGAVCompletionItem(new GroupIdArtifactId(mavenProject.getGroupId(), mavenProject.getArtifactId()), new DefaultArtifactVersion(mavenProject.getVersion()), mavenProject.getDescription(), request, InsertionStrategy.ELEMENT_VALUE_AND_SIBLING)).ifPresent(response::addCompletionItem);
				}
				// TODO localRepo
				// TODO remoteRepos
			} else {
				(isPlugin ? localRepositorySearcher.getLocalPluginArtifacts() : localRepositorySearcher.getLocalArtifactsLastVersion()).entrySet().stream()
					.filter(entry -> !groupId.isPresent() || entry.getKey().groupId.equals(groupId.get()))
					// TODO pass description as documentation
					.map(entry -> toGAVCompletionItem(entry.getKey(), entry.getValue(), null, request, InsertionStrategy.ELEMENT_VALUE_AND_SIBLING))
					.forEach(response::addCompletionItem);
				internalCollectRemoteGAVCompletion(request, isPlugin, gavInsertionStrategy).forEach(response::addCompletionItem);
			}
			break;
		case "version":
			if (!isParentDeclaration) {
				if (artifactId.isPresent()) {
					localRepositorySearcher.getLocalArtifactsLastVersion().entrySet().stream()
						.filter(entry -> entry.getKey().artifactId.equals(artifactId.get()))
						.filter(entry -> !groupId.isPresent() || entry.getKey().groupId.equals(groupId.get()))
						.findAny()
						.map(Entry::getValue)
						.map(version -> toCompletionItem(version.toString(), null, request.getReplaceRange()))
						.ifPresent(response::addCompletionItem);
					internalCollectRemoteGAVCompletion(request, isPlugin, gavInsertionStrategy).forEach(response::addCompletionItem);
				}
			} else {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(mavenProject -> toGAVCompletionItem(new GroupIdArtifactId(mavenProject.getGroupId(), mavenProject.getArtifactId()), new DefaultArtifactVersion(mavenProject.getVersion()), mavenProject.getDescription(), request, null)).ifPresent(response::addCompletionItem);
				}
				// TODO localRepo
				// TODO remoteRepos
			}
			break;
		case "module":
			collectSubModuleCompletion(request, response);
			break;
		case "dependencies":
		case "dependency":
			localRepositorySearcher.getLocalArtifactsLastVersion().entrySet().stream()
				// TODO pass description as documentation
				.map(entry -> toGAVCompletionItem(entry.getKey(), entry.getValue(), null, request, gavInsertionStrategy))
				.forEach(response::addCompletionItem);
			internalCollectRemoteGAVCompletion(request, false, gavInsertionStrategy).forEach(response::addCompletionItem);
			// Break commented out for now so that snippets can be available
			// break;
			break;
		case "plugins":
		case "plugin":
			localRepositorySearcher.getLocalPluginArtifacts().entrySet().stream()
				// TODO pass description as documentation
				.map(entry -> toGAVCompletionItem(entry.getKey(), entry.getValue(), null, request, gavInsertionStrategy))
				.forEach(response::addCompletionItem);
			internalCollectRemoteGAVCompletion(request, true, gavInsertionStrategy).forEach(response::addCompletionItem);
			break;
		case "parent":
			Optional<MavenProject> filesystem = computeFilesystemParent(request);
			if (filesystem.isPresent()) {
				filesystem.map(mavenProject -> toGAVCompletionItem(new GroupIdArtifactId(mavenProject.getGroupId(), mavenProject.getArtifactId()), new DefaultArtifactVersion(mavenProject.getVersion()), mavenProject.getDescription(), request, InsertionStrategy.CHILDREN_ELEMENTS))
					.ifPresent(response::addCompletionItem);
			} else {
				// TODO localRepo
				// TODO remoteRepos
			}
			break;
		case "goal":
			collectGoals(request).forEach(response::addCompletionItem);
			break;
		case "configuration":
			MavenPluginUtils.collectPluginConfigurationParameters(request, cache, pluginManager).stream()
					.map(parameter -> toTag(parameter.getName(), MavenPluginUtils.getMarkupDescription(parameter), request))
					.forEach(response::addCompletionItem);
			break;
//		case "relativePath":
//			collectLocalPaths(request).stream().map(toCompletionItem(label, description, range))
//			break;
		default:
			initSnippets();
			TextDocument document = parent.getOwnerDocument().getTextDocument();
			int completionOffset = request.getOffset();
			boolean canSupportMarkdown = true; // request.canSupportMarkupKind(MarkupKind.MARKDOWN);
			SnippetRegistry.getInstance()
					.getCompletionItems(document, completionOffset, canSupportMarkdown, context -> {
						if (!Maven.POMv4.equals(context.getType())) {
							return false;
						}
						return parent.getLocalName().equals(context.getValue());
					}).forEach(response::addCompletionItem);
		}
		if (request.getNode().isText()) {
			completeProperties(request).forEach(response::addCompletionAttribute);
		}
	}

	private InsertionStrategy computeGAVInsertionStrategy(ICompletionRequest request) {
		if (request.getParentElement() == null) {
			return null;
		}
		switch (request.getParentElement().getLocalName()) {
		case "dependencies": return new InsertionStrategy.NodeWithChildrenInsertionStrategy("dependency");
		case "dependency": return InsertionStrategy.CHILDREN_ELEMENTS;
		case "plugins": return new InsertionStrategy.NodeWithChildrenInsertionStrategy("plugin");
		case "plugin": return InsertionStrategy.CHILDREN_ELEMENTS;
		case "artifactId": return InsertionStrategy.ELEMENT_VALUE_AND_SIBLING;
		}
		return InsertionStrategy.ELEMENT_VALUE_AND_SIBLING;
	}

	private Optional<MavenProject> computeFilesystemParent(ICompletionRequest request) {
		Optional<String> relativePath = null;
		if (request.getParentElement().getLocalName().equals("parent")) {
			relativePath = DOMUtils.findChildElementText(request.getNode(), "relativePath");
		} else {
			relativePath = DOMUtils.findChildElementText(request.getParentElement().getParentElement(), "relativePath");
		}
		if (!relativePath.isPresent()) {
			relativePath = Optional.of("..");
		}
		File referencedTargetPomFile = new File(new File(URI.create(request.getXMLDocument().getTextDocument().getUri())).getParentFile(), relativePath.orElse(""));
		if (referencedTargetPomFile.isDirectory()) {
			referencedTargetPomFile = new File(referencedTargetPomFile, Maven.POMv4);
		}
		if (referencedTargetPomFile.isFile()) {
			return cache.getSnapshotProject(referencedTargetPomFile);
		}
		return Optional.empty();
	}

	private CompletionItem createMinimalPOMCompletionSnippet(ICompletionRequest request) throws IOException, BadLocationException {
		CompletionItem item = new CompletionItem("minimal pom content");
		item.setKind(CompletionItemKind.Snippet);
		item.setInsertTextFormat(InsertTextFormat.Snippet);
		Model model = new Model();
		model.setArtifactId("$0");
		MavenXpp3Writer writer = new MavenXpp3Writer();
		try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
			writer.write(stream, model);
			TextEdit textEdit = new TextEdit(new Range(new Position(0, 0), request.getXMLDocument().positionAt(request.getXMLDocument().getText().length())), new String(stream.toByteArray()));
			item.setTextEdit(textEdit);
		}
		return item;
	}

	private Collection<CompletionItem> collectGoals(ICompletionRequest request) {
		PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request, cache, pluginManager);
		if (pluginDescriptor != null) {
			return collectSimpleCompletionItems(pluginDescriptor.getMojos(), MojoDescriptor::getGoal, MojoDescriptor::getDescription, request);
		}
		return Collections.emptySet();
	}

	private CompletionItem toGAVCompletionItem(GroupIdArtifactId groupIdArtifactId, ArtifactVersion version, String description, ICompletionRequest request, InsertionStrategy strategy) {
		boolean insertGroupId = strategy instanceof org.eclipse.lemminx.maven.MavenCompletionParticipant.InsertionStrategy.NodeWithChildrenInsertionStrategy || !DOMUtils.findChildElementText(request.getParentElement().getParentElement(), "groupId").isPresent();
		boolean insertVersion = strategy instanceof org.eclipse.lemminx.maven.MavenCompletionParticipant.InsertionStrategy.NodeWithChildrenInsertionStrategy || !DOMUtils.findChildElementText(request.getParentElement().getParentElement(), "version").isPresent();
		CompletionItem item = new CompletionItem();
		if (description != null) {
			item.setDocumentation(description);
		}
		TextEdit textEdit = new TextEdit();
		item.setTextEdit(textEdit);
		textEdit.setRange(request.getReplaceRange());
		if (strategy == InsertionStrategy.ELEMENT_VALUE_AND_SIBLING) {
			item.setLabel(groupIdArtifactId.artifactId);
			textEdit.setRange(request.getReplaceRange());
			textEdit.setNewText(groupIdArtifactId.artifactId);
			item.setKind(CompletionItemKind.Value);
			List<TextEdit> additionalEdits = new ArrayList<>(2);
			if (insertGroupId) {
				Position insertionPosition;
				try {
					insertionPosition = request.getXMLDocument().positionAt(request.getParentElement().getParentElement().getStartTagCloseOffset() + 1);
					additionalEdits.add(new TextEdit(new Range(insertionPosition, insertionPosition), request.getLineIndentInfo().getLineDelimiter() + request.getLineIndentInfo().getWhitespacesIndent() + "<groupId>" + groupIdArtifactId.groupId + "</groupId>"));
				} catch (BadLocationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (insertVersion) {
				Position insertionPosition;
				try {
					insertionPosition = request.getXMLDocument().positionAt(request.getParentElement().getEndTagCloseOffset() + 1);
					additionalEdits.add(new TextEdit(new Range(insertionPosition, insertionPosition), request.getLineIndentInfo().getLineDelimiter() + request.getLineIndentInfo().getWhitespacesIndent() + DOMUtils.getOneLevelIndent(request) + "<version>" + version.toString() + "</version>"));
				} catch (BadLocationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (!additionalEdits.isEmpty()) {
				item.setAdditionalTextEdits(additionalEdits);
			}
		} else {
			item.setLabel(groupIdArtifactId.artifactId + " - " + groupIdArtifactId.groupId + ":" + groupIdArtifactId.artifactId + ":" + version.toString());
			item.setKind(CompletionItemKind.Struct);
			try {
				textEdit.setRange(request.getReplaceRange());
				String newText = "";
				String suffix = ""; 
				String gavElementsIndent = request.getLineIndentInfo().getWhitespacesIndent(); 
				if (strategy instanceof InsertionStrategy.NodeWithChildrenInsertionStrategy) {
					String elementName = ((InsertionStrategy.NodeWithChildrenInsertionStrategy)strategy).elementName;
					gavElementsIndent += DOMUtils.getOneLevelIndent(request);
					newText += "<" + elementName + ">" + request.getLineIndentInfo().getLineDelimiter() + gavElementsIndent;
					suffix = request.getLineIndentInfo().getLineDelimiter() + request.getLineIndentInfo().getWhitespacesIndent() + "</" + elementName + ">";
				}
				if (insertGroupId) {
					newText += "<groupId>" + groupIdArtifactId.groupId + "</groupId>" + request.getLineIndentInfo().getLineDelimiter() + gavElementsIndent;
				}
				newText += "<artifactId>" + groupIdArtifactId.artifactId + "</artifactId>";
				if (insertVersion) {
					newText += request.getLineIndentInfo().getLineDelimiter() + gavElementsIndent;
					newText += "<version>" + version + "</version>";
				}
				newText += suffix;
				textEdit.setNewText(newText);
			} catch (BadLocationException ex) {
				ex.printStackTrace();
				return null;
			}
		}
		return item;
	}

	private CompletionItem toTag(String name, MarkupContent description, ICompletionRequest request) {
		CompletionItem res = new CompletionItem(name);
		res.setDocumentation(Either.forRight(description));
		res.setInsertTextFormat(InsertTextFormat.Snippet);
		TextEdit edit = new TextEdit();
		edit.setNewText('<' + name + ">$0</" + name + '>');
		edit.setRange(request.getReplaceRange());
		res.setTextEdit(edit);
		res.setKind(CompletionItemKind.Field);
		return res;
	}

	private Collection<CompletionItem> completeProperties(ICompletionRequest request) {
		DOMDocument xmlDocument = request.getXMLDocument();
		String documentText = xmlDocument.getText();
		int initialPropertyOffset = request.getOffset();
		for (int i = request.getOffset() - 1; i >= request.getNode().getStart(); i--) {
			char currentChar = documentText.charAt(i);
			if (currentChar == '}') {
				// properties area ended, return all properties
				break;
			} else if (currentChar == '$') {
				initialPropertyOffset = i;
				break;
			}
		}
		Map<String, String> allProps = new HashMap<>();
		MavenProject project = cache.getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project != null && project.getProperties() != null) {
			for (Entry<Object, Object> prop : project.getProperties().entrySet()) {
				allProps.put((String) prop.getKey(), (String) prop.getValue());
			}
		}
		allProps.put("basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.version", project == null ? "unknown" : project.getVersion());
		allProps.put("project.groupId", project == null ? "unknown" : project.getGroupId());
		allProps.put("project.artifactId", project == null ? "unknown" : project.getArtifactId());
		allProps.put("project.name", project == null ? "unknown" : project.getName());
		allProps.put("project.build.directory", project.getBuild() == null ? "unknown" : project.getBuild().getDirectory());
		allProps.put("project.build.outputDirectory",
				project.getBuild() == null ? "unknown" : project.getBuild().getOutputDirectory());

		final int offset = initialPropertyOffset;
		return allProps.entrySet().stream().map(property -> {
			CompletionItem item = new CompletionItem();
			item.setLabel("${" + property.getKey() + '}');
			item.setDocumentation("Default Value: " + (property.getValue() != null ? property.getValue() : "unknown"));
			try {
				TextEdit textEdit = new TextEdit();
				textEdit.setNewText(item.getLabel());
				Range range = new Range(xmlDocument.positionAt(offset),
						xmlDocument.positionAt(request.getOffset()));
				textEdit.setRange(range);
				item.setTextEdit(textEdit);
			} catch (BadLocationException e) {
				e.printStackTrace();
				item.setInsertText(item.getLabel());
			}
			return item;
		}).collect(Collectors.toList());
	}
	
	private Collection<CompletionItem> internalCollectRemoteGAVCompletion(ICompletionRequest request, boolean onlyPlugins, InsertionStrategy insertionStrategy) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);
		List<String> remoteArtifactRepositories = Collections.singletonList(RemoteRepositoryIndexSearcher.CENTRAL_REPO.getUrl());
		Dependency artifactToSearch = MavenParseUtils.parseArtifact(node);
		MavenProject project = cache.getLastSuccessfulMavenProject(doc);
		if (project != null) {
			remoteArtifactRepositories = project.getRemoteArtifactRepositories().stream().map(ArtifactRepository::getUrl).collect(Collectors.toList());
		}
		Collection<CompletionItem> items = Collections.synchronizedSet(new LinkedHashSet<>());
		try {
			CompletableFuture.allOf(remoteArtifactRepositories.stream().map(repository -> {
				final CompletionItem updatingItem = new CompletionItem("Updating index for " + repository);
				updatingItem.setPreselect(true);
				updatingItem.setInsertText("");
				updatingItem.setKind(CompletionItemKind.Event);
				items.add(updatingItem);
				return indexSearcher.getIndexingContext(URI.create(repository)).thenAcceptAsync(index -> {
					switch (node.getLocalName()) {
					case "groupId":
						// TODO: just pass only plugins boolean, and make getGroupId's accept a boolean parameter
						if (onlyPlugins) {
							indexSearcher.getPluginGroupIds(artifactToSearch, index).stream()
									.map(groupId -> toCompletionItem(groupId, null, range)).forEach(items::add);
						} else {
							indexSearcher.getGroupIds(artifactToSearch, index).stream()
									.map(groupId -> toCompletionItem(groupId, null, range)).forEach(items::add);
						}
						return;
					case "artifactId":
						if (onlyPlugins) {
							indexSearcher.getPluginArtifactIds(artifactToSearch, index).stream()
									.map(artifactInfo -> toGAVCompletionItem(new GroupIdArtifactId(artifactInfo.getGroupId(), artifactInfo.getArtifactId()), new DefaultArtifactVersion(artifactInfo.getVersion()),
											artifactInfo.getDescription(), request, insertionStrategy))
									.forEach(items::add);
						} else {
							indexSearcher.getArtifactIds(artifactToSearch, index).stream()
									.map(artifactInfo -> toGAVCompletionItem(new GroupIdArtifactId(artifactInfo.getGroupId(), artifactInfo.getArtifactId()), new DefaultArtifactVersion(artifactInfo.getVersion()),
											artifactInfo.getDescription(), request, insertionStrategy))
									.forEach(items::add);
						}
						return;
					case "version":
						if (onlyPlugins) {
							indexSearcher.getPluginArtifactVersions(artifactToSearch, index).stream()
									.map(version -> toCompletionItem(version.toString(), null, range))
									.forEach(items::add);
						} else {
							indexSearcher.getArtifactVersions(artifactToSearch, index).stream()
									.map(version -> toCompletionItem(version.toString(), null, range))
									.forEach(items::add);
						}
						return;
					case "dependencies":
						indexSearcher.getArtifactIds(artifactToSearch, index).stream()
							.map(artifactInfo -> toGAVCompletionItem(new GroupIdArtifactId(artifactInfo.getGroupId(), artifactInfo.getArtifactId()), new DefaultArtifactVersion(artifactInfo.getVersion()),
									artifactInfo.getDescription(), request, insertionStrategy))
							.forEach(items::add);
						return;
					case "plugins":
						indexSearcher.getPluginArtifactIds(artifactToSearch, index).stream()
							.map(artifactInfo -> toGAVCompletionItem(new GroupIdArtifactId(artifactInfo.getGroupId(), artifactInfo.getArtifactId()), new DefaultArtifactVersion(artifactInfo.getVersion()),
									artifactInfo.getDescription(), request, insertionStrategy))
							.forEach(items::add);
						return;
					}
				}).whenComplete((ok, error) -> items.remove(updatingItem));
			}).toArray(CompletableFuture<?>[]::new)).get(2, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException exception) {
			exception.printStackTrace();
		} catch (TimeoutException e) {
			// nothing to log, some work still pending
		}
		return items;
	}

	private void collectSubModuleCompletion(ICompletionRequest request, ICompletionResponse response) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);
		MavenProject mavenProject = cache.getLastSuccessfulMavenProject(doc);
		if (mavenProject == null) {
			return;
		}
		Model model = mavenProject.getModel();
		for (String module : model.getModules()) {
			response.addCompletionItem(toCompletionItem(module, "", range));
		}

	}

	private void initSnippets() {
		if (snippetsLoaded) {
			return;
		}
		try {
			try {
				SnippetRegistry.getInstance()
						.load(MavenCompletionParticipant.class.getResourceAsStream("pom-snippets.json"));
			} catch (IOException e) {
				e.printStackTrace();
			}
		} finally {
			snippetsLoaded = true;
		}

	}

	private <T> Collection<CompletionItem> collectSimpleCompletionItems(Collection<T> items, Function<T, String> insertionTextExtractor,
			Function<T, String> documentationExtractor, ICompletionRequest request) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();
		boolean needClosingTag = node.getEndTagOpenOffset() == DOMNode.NULL_VALUE;
		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1,
				needClosingTag ? node.getStartTagOpenOffset() + 1 : node.getEndTagOpenOffset(), doc);

		return items.stream().map(o -> {
			String label = insertionTextExtractor.apply(o);
			CompletionItem item = new CompletionItem();
			item.setLabel(label);
			String insertText = label + (needClosingTag ? "</" + node.getTagName() + ">" : "");
			item.setKind(CompletionItemKind.Property);
			item.setDocumentation(Either.forLeft(documentationExtractor.apply(o)));
			item.setFilterText(insertText);
			item.setTextEdit(new TextEdit(range, insertText));
			item.setInsertTextFormat(InsertTextFormat.PlainText);
			return item;
		}).collect(Collectors.toList());
	}

	/**
	 * Utility function, takes a label string, description and range and returns a
	 * CompletionItem
	 * 
	 * @param description Completion description
	 * @param label       Completion label
	 * @return CompletionItem resulting from the label, description and range given
	 * @param range Range where the completion will be inserted
	 */
	private static CompletionItem toCompletionItem(String label, String description, Range range) {
		CompletionItem item = new CompletionItem();
		item.setLabel(label);
		item.setSortText(label);
		item.setKind(CompletionItemKind.Property);
		String insertText = label;
		if (description != null) {
			item.setDocumentation(Either.forLeft(description));
		}
		item.setFilterText(insertText);
		item.setInsertTextFormat(InsertTextFormat.PlainText);
		item.setTextEdit(new TextEdit(range, insertText));
		return item;
	}
}
