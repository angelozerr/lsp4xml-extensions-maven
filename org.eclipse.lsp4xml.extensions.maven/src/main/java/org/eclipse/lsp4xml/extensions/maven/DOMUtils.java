/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven;

import java.util.Optional;

import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.services.extensions.IPositionRequest;

public class DOMUtils {

	public static DOMNode findClosestParentNode(final IPositionRequest request, final String localName) {
		if (localName == null || request == null) {
			return null;
		}
		DOMNode pluginNode = request.getNode();
		try {
			while (!localName.equals(pluginNode.getLocalName())) {
				pluginNode = pluginNode.getParentNode();
			}
		} catch (NullPointerException e) {
			return null;
		}

		if (localName.equals(pluginNode.getLocalName())) {
			return pluginNode;
		}
		return null;
	}

	public static Optional<String> findChildElementText(DOMNode pluginNode, final String elementName) {
		return pluginNode.getChildren().stream().filter(node -> elementName.equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream()).findAny().map(DOMNode::getTextContent).map(String::trim);
	}
}
