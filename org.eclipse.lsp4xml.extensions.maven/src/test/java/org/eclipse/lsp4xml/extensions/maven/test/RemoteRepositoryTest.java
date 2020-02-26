/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.test;

import static org.eclipse.lsp4xml.extensions.maven.test.MavenLemminxTestsUtils.completionContains;
import static org.eclipse.lsp4xml.extensions.maven.test.MavenLemminxTestsUtils.createTextDocumentItem;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteRepositoryTest {

	/* This needs to be static because of https://github.com/angelozerr/lsp4xml/issues/610 */
	private static ClientServerConnection connection;

	@BeforeClass
	public static void setUp() throws IOException {
		connection = new ClientServerConnection();
	}

	@AfterClass
	public static void tearDown() throws InterruptedException {
		connection.stop();
	}


	@Test(timeout=120000)
	public void testRemoteGroupIdCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-groupId-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		final Position pos = new Position(11, 20);
		String desiredCompletion = "signaturacaib";
		List<CompletionItem> items = Collections.emptyList();
		do {
			items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		} while (!completionContains(items, desiredCompletion));
		assertTrue(completionContains(items, desiredCompletion));
	}
	
	@Test(timeout=120000)
	public void testRemoteArtifactIdCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-artifactId-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		final Position pos = new Position(12, 15);
		String desiredCompletion = "signaturacaib.core";
		List<CompletionItem> items = Collections.emptyList();
		do {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		} while (!completionContains(items, desiredCompletion));
		assertTrue(completionContains(items, desiredCompletion));
	}
	
	@Test(timeout=120000)
	public void testRemoteVersionCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-version-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Position pos = new Position(13, 13);
		String desiredCompletion = "3.3.0";
		List<CompletionItem> items = Collections.emptyList();
		do {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		} while (!completionContains(items, desiredCompletion));
		assertTrue(completionContains(items, desiredCompletion));
	}

}
