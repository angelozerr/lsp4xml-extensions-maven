/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.test;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class ExtensionTest {

	private ClientServerConnection connection;

	@Before
	public void setUp() throws IOException {
		connection = new ClientServerConnection();
	}

	@After
	public void tearDown() {
		connection.stop();
	}

	@Test public void testScopeCompletion() throws IOException, InterruptedException, ExecutionException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-module-error.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), new Position(12, 10))).get();
		assertTrue(completion.getRight().getItems().stream().map(CompletionItem::getLabel).anyMatch("runtime"::equals));
	}

	@Test public void testPropertyCompletion() throws IOException, InterruptedException, ExecutionException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-properties.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), new Position(10, 15))).get();
		List<CompletionItem> items = completion.getRight().getItems();
		assertTrue(items.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("myProperty")));
		// TODO standard properties aren't supported yet
		//assertTrue(items.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("project.build.directory")));
	}

	@Test public void testMissingArtifactIdError() throws IOException, InterruptedException, ExecutionException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-without-artifactId.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		assertTrue(connection.waitForDiagnostics(diagnostics -> diagnostics.stream().map(Diagnostic::getMessage).anyMatch(message -> message.contains("artifactId")), 5000));
	}

	TextDocumentItem createTextDocumentItem(String resourcePath) throws IOException {
		File tempDir = Files.createTempDirectory("test").toFile();
		File target = new File(tempDir, "pom.xml");
		InputStream stream = getClass().getResourceAsStream(resourcePath);
		java.nio.file.Files.copy(stream, target.toPath());
		return new TextDocumentItem(target.toURI().toString(), "xml", 1, new String(Files.readAllBytes(target.toPath())));
	}

}
