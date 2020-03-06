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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.services.extensions.IHoverParticipant;
import org.eclipse.lsp4xml.services.extensions.IHoverRequest;
import org.eclipse.lsp4xml.services.extensions.IPositionRequest;

public class MavenHoverParticipant implements IHoverParticipant {
	private final MavenProjectCache cache;
	private final MavenPluginManager pluginManager;

	public MavenHoverParticipant(MavenProjectCache cache, MavenPluginManager pluginManager) {
		this.cache = cache;
		this.pluginManager = pluginManager;
	}

	@Override
	public Hover onAttributeName(IHoverRequest request) throws Exception {
		return null;
	}

	@Override
	public Hover onAttributeValue(IHoverRequest request) throws Exception {
		return null;
	}

	@Override
	public Hover onTag(IHoverRequest request) throws Exception {
		DOMNode node = request.getNode();

		if (node == null || node.getLocalName() == null) {
			return null;
		}
		Hover response = new Hover();
		response.setContents(new MarkupContent("plaintext", "Empty"));
		if (node.getParentElement() != null) {
			switch (node.getParentElement().getLocalName()) {
			case "configuration":
				return collectPuginConfiguration(request);
			case "goals":
				return collectGoals(request);
			default:
				break;
			}
		}
		
		return null;
	}

	private Hover collectGoals(IPositionRequest request) {
		DOMNode node = request.getNode();
		PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request, cache, pluginManager);
		
		for (MojoDescriptor mojo : pluginDescriptor.getMojos()) {
			if (!node.getChild(0).getNodeValue().trim().isEmpty() && node.hasChildNodes()
					&& node.getChild(0).getNodeValue().equals(mojo.getGoal())) {
				Hover hover = new Hover();
				hover.setContents(new MarkupContent("plaintext", mojo.getDescription()));
				return hover;
			}
		}
		return null;
	}

	private Hover collectPuginConfiguration(IPositionRequest request) {
		List<Parameter> parameters = MavenPluginUtils.collectPluginConfigurationParameters(request, cache, pluginManager);
		DOMNode node = request.getNode();
		
		for (Parameter parameter : parameters) {
			if (node.getLocalName().equals(parameter.getName())) {
				Hover hover = new Hover();
				hover.setContents(MavenPluginUtils.getMarkupDescription(parameter));
				return hover;
			}
		}
		return null;
	}
	
}
