/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LocalArtifactSearcher implements IArtifactSearcher {

	private static Path MAVEN_LOCAL_REPOSITORY = Paths.get(System.getProperty("user.home"), ".m2", "repository");

	@Override
	public Set<String> searchGroupIds(String groupIdHint) {
		if (Files.exists(MAVEN_LOCAL_REPOSITORY)) {
			Set<String> groupIds = new HashSet<>();
			FileVisitor<Path> fv = new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					return super.visitFile(file, attrs);
				}

				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (MAVEN_LOCAL_REPOSITORY.equals(dir)) {
						return FileVisitResult.CONTINUE;
					}
					if (dir.getFileName().toString().startsWith(".")) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					groupIds.add(dir.getFileName().toString());
					return FileVisitResult.SKIP_SUBTREE;
				}
			};

			try {
				Files.walkFileTree(MAVEN_LOCAL_REPOSITORY, fv);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return groupIds;

		}
		return Collections.emptySet();
	}

}
