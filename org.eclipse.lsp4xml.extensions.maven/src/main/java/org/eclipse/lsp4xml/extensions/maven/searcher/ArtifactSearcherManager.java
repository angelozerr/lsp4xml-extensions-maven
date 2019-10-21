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
