package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.util.Set;

public interface IArtifactSearcher {

	Set<String> searchGroupIds(String groupIdHint);
}
