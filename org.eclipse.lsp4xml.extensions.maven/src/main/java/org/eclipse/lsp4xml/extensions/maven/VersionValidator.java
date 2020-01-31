/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMNode;

public class VersionValidator {

	public static Artifact parseArtifact(DOMNode node) {
		String groupId = "MissingGroupID";
		String artifactId = "MissingArtifactID";
		String version = "1.0.0";
		String scope = "compile"; // Default scope if no scope is specified
		String type = "jar"; // Default type is jar if no type is specified
		String classifier = null; // Default classifier is null
		try {
			for (DOMNode tag : node.getParentElement().getChildren()) {
				if (tag != null && tag.hasChildNodes() && !tag.getChild(0).getNodeValue().trim().isEmpty()) {
					switch (tag.getLocalName()) {
					case "groupId":
						groupId = tag.getChild(0).getNodeValue();
						break;
					case "artifactId":
						artifactId = tag.getChild(0).getNodeValue();
						break;
					case "version":
						version = tag.getChild(0).getNodeValue();
						break;
					case "scope":
						scope = tag.getChild(0).getNodeValue();
						break;
					case "type":
						type = tag.getChild(0).getNodeValue();
						break;
					case "classifier":
						classifier = tag.getChild(0).getNodeValue();
						break;
					}
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Error parsing Artifact");
		}
		return new DefaultArtifact(groupId, artifactId, version, scope, type, classifier,
				new DefaultArtifactHandler(type));
	}

	public static Diagnostic validateVersion(DiagnosticRequest diagnosticRequest) {
		DOMNode node = diagnosticRequest.getNode();
		DOMDocument xmlDocument = diagnosticRequest.getDOMDocument();
		Artifact artifact = parseArtifact(node);
		Diagnostic diagnostic = null;
		Range range = diagnosticRequest.getRange();
		try {
			// TODO: This class doesn't work as intended - isSelectedVersionKnown() doesn't
			// actually verify the version is legal
			if (!artifact.isSelectedVersionKnown()) {
				diagnostic = new Diagnostic(range, "Version Error", DiagnosticSeverity.Error,
						xmlDocument.getDocumentURI(), "XML");
				diagnosticRequest.getDiagnostics().add(diagnostic);
			}
		} catch (OverConstrainedVersionException e) {
			// TODO: Use plug-in error logger
			e.printStackTrace();
		}
		return diagnostic;
	}
}
