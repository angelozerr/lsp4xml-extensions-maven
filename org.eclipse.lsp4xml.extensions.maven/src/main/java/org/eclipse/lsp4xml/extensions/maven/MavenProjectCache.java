/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.repository.legacy.LegacyRepositorySystem;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.lsp4xml.dom.DOMDocument;

public class MavenProjectCache {

	private final Map<URI, Integer> lastCheckedVersion;
	private final Map<URI, MavenProject> projectCache;
	private final Map<URI, Collection<ModelProblem>> problemCache;
	private final PlexusContainer plexusContainer;

	public MavenProjectCache(PlexusContainer container) {
		this.plexusContainer = container;
		this.lastCheckedVersion = new HashMap<URI, Integer>();
		this.projectCache = new HashMap<URI, MavenProject>();
		this.problemCache = new HashMap<URI, Collection<ModelProblem>>();
	}

	/**
	 * 
	 * @param document
	 * @return the last MavenDocument that could be build for the more recent version of the provided document. If document
	 * fails to build a MavenProject, a former version will be returned. Can be <code>null</code>. 
	 */
	public MavenProject getLastSuccessfulMavenProject(DOMDocument document) {
		check(document);
		return projectCache.get(URI.create(document.getTextDocument().getUri()));
	}
	
	/**
	 * 
	 * @param document
	 * @return the problems for the latest version of the document (either in cache, or the one passed in arguments)
	 */
	public Collection<ModelProblem> getProblemsFor(DOMDocument document) {
		check(document);
		return problemCache.get(URI.create(document.getTextDocument().getUri()));
	}

	private void check(DOMDocument document) {
		Integer last = lastCheckedVersion.get(URI.create(document.getTextDocument().getUri()));
		if (last == null || last.intValue() < document.getTextDocument().getVersion()) {
			parse(document);
		}
	}

	private void parse(DOMDocument document) {
		URI uri = URI.create(document.getDocumentURI());
		File workingCopy = null;
		Collection<ModelProblem> problems = new ArrayList<ModelProblem>();
		MavenProject project = null;
		try {
			ProjectBuilder projectBuilder = plexusContainer.lookup(ProjectBuilder.class);
			File file = new File(uri);
			workingCopy = File.createTempFile("workingCopy", '.' + file.getName(), file.getParentFile());
			Files.copy(file.toPath(), workingCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
			ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
			//request.setLocalRepository(localRepository)
			RepositorySystem repositorySystem = plexusContainer.lookup(RepositorySystem.class);
			request.setLocalRepository(repositorySystem.createDefaultLocalRepository());
			ProjectBuildingResult buildResult = projectBuilder.build(workingCopy, request);
			problems.addAll(buildResult.getProblems());
			if (buildResult.getProject() != null) {
				projectCache.put(uri, buildResult.getProject());
			}
		} catch (ProjectBuildingException e) {
			if (e.getResults() == null) {
				if (e.getCause() instanceof ModelBuildingException) {
					ModelBuildingException modelBuildingException = (ModelBuildingException)e.getCause();
					problems.addAll(modelBuildingException.getProblems());
				} else {
					problems.add(new DefaultModelProblem(e.getMessage(), Severity.FATAL, Version.BASE, null, -1, -1, e));
				}
			} else {
				e.getResults().stream().flatMap(result -> result.getProblems().stream()).forEach(problems::add);
			}
		} catch (ComponentLookupException | IOException | InvalidRepositoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (workingCopy != null) {
			workingCopy.delete();
		}

		lastCheckedVersion.put(uri, document.getTextDocument().getVersion());
		problemCache.put(uri, problems);
	}

}
