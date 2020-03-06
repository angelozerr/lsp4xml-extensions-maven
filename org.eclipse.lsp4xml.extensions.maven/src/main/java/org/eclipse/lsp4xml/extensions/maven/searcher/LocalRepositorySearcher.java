/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class LocalRepositorySearcher {

	private Map<File, Map<Entry<String, String>, ArtifactVersion>> cache = new HashMap<File, Map<Entry<String,String>,ArtifactVersion>>();

	public Set<String> searchGroupIds(File localRepository) throws IOException {
		return getLocalArtifacts(localRepository).keySet().stream().map(Entry::getKey).collect(Collectors.toSet());
	}

	public Map<Entry<String, String>, ArtifactVersion> getLocalArtifacts(File localRepository) throws IOException {
		Map<Entry<String, String>, ArtifactVersion> res = cache.get(localRepository);
		if (res == null) {
			res = computeLocalArtifacts(localRepository);
			Path localRepoPath = localRepository.toPath();
			WatchService watchService = localRepoPath.getFileSystem().newWatchService();
			localRepoPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
			new Thread(() -> {
				WatchKey key;
				try {
					while ((key = watchService.take()) != null) {
						cache.remove(localRepository);
						key.reset();
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}).start();
			cache.put(localRepository, res);
		}
		return res;
	}

	public Map<Entry<String, String>, ArtifactVersion> computeLocalArtifacts(File localRepository) throws IOException {
		final Path repoPath = localRepository.toPath();
		Map<Entry<String, String>, ArtifactVersion> groupIdArtifactIdToVersion = new HashMap<>();
		Files.walkFileTree(repoPath, Collections.emptySet(), 10, new SimpleFileVisitor<Path>() { 
			@Override
			public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.getFileName().toString().charAt(0) == '.') {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (Character.isDigit(file.getFileName().toString().charAt(0))) {
					Path artifactFolderPath = repoPath.relativize(file);
					ArtifactVersion version = new DefaultArtifactVersion(artifactFolderPath.getFileName().toString());
					String artifactId = artifactFolderPath.getParent().getFileName().toString();
					String groupId = artifactFolderPath.getParent().getParent().toString().replace(artifactFolderPath.getFileSystem().getSeparator(), ".");
					Entry<String, String> groupIdArtifactId = new SimpleEntry<>(groupId, artifactId);
					ArtifactVersion existingVersion = groupIdArtifactIdToVersion.get(groupIdArtifactId);
					if (existingVersion == null || existingVersion.compareTo(version) < 0 || (!version.toString().endsWith("-SNAPSHOT") && existingVersion.toString().endsWith("-SNAPSHOT"))) {
						groupIdArtifactIdToVersion.put(groupIdArtifactId, version);
					}
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return groupIdArtifactIdToVersion;
	}

}
