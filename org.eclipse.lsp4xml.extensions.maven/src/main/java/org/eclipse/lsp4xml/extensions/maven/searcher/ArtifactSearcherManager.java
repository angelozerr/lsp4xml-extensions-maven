/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.util.Set;

public class ArtifactSearcherManager {

	private static final ArtifactSearcherManager INSTANCE = new ArtifactSearcherManager();

	private final IArtifactSearcher localSearcher;

	public ArtifactSearcherManager() {
		localSearcher = new LocalArtifactSearcher();
	}

	public static ArtifactSearcherManager getInstance() {
		return INSTANCE;
	}

	public Set<String> searchLocalGroupIds(String groupIdHint) {
		return localSearcher.searchGroupIds(groupIdHint);
	}

}
