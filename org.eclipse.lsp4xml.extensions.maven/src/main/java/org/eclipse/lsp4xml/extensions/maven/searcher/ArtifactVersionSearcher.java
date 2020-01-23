package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.Field;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.project.MavenProject;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.Version;
import org.eclipse.lsp4xml.extensions.maven.MavenRepositoryCache;

public class ArtifactVersionSearcher {
	public static final int COMPARISON_TYPE_GREATER = 1; // 0001
	public static final int COMPARISON_TYPE_LESS = 2; // 0010
	public static final int COMPARISON_TYPE_EQUALS = 4; // 0100

	private static final ArtifactVersionSearcher INSTANCE = new ArtifactVersionSearcher();

	LocalRepositoryManager localRepoMan;

	private Indexer indexer;

	private IndexUpdater indexUpdater;

	private List<IndexCreator> indexers;
	
	private String targetPath;

	private Wagon httpWagon;

	private HashMap<String, IndexingContext> indexingContexts = new HashMap<>();

	private List<String> artifactVersions = Collections.synchronizedList(new ArrayList<>());

	private CompletableFuture<Void> syncReq = null;

	private Set<String> brokenContexts = Collections.synchronizedSet(new HashSet<String>());

	private ArtifactVersionSearcher() {
		this.httpWagon = null;
		this.indexUpdater = null;
		this.indexer = null;
		this.indexers = new ArrayList<>();
	}

	public static ArtifactVersionSearcher getInstance() {
		return INSTANCE;
	}

	// Must be called before this class can be used
	public void init(PlexusContainer plexusContainer, MavenProject project) throws ComponentLookupException {
		indexer = (indexer == null) ?  plexusContainer.lookup(Indexer.class) : indexer;
		indexUpdater =  (indexUpdater == null) ? plexusContainer.lookup(IndexUpdater.class) : indexUpdater;
		httpWagon = httpWagon == null ? plexusContainer.lookup(Wagon.class, "http") : httpWagon;
		
		if (indexers.isEmpty()) {
			indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));			
		}

		targetPath = project.getBuild().getDirectory() + System.getProperty("path.separator");

	}

	public List<String> getArtifactVersions(Artifact artifactToSearch) {
		if (syncReq == null) {
			syncReq = syncIndex().handleAsync((a, b) -> {
				if (b != null) {
					b.printStackTrace();
				}
				return a;
			});

			try {
				syncReq.get(500, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				e.printStackTrace();
				String interrupted = "Error: Index updated interrupted";
				artifactVersions.clear();
				artifactVersions.add(interrupted);
				return artifactVersions;
			} catch (ExecutionException e) {
				e.printStackTrace();
				String error = "Error: Index update error occured";
				artifactVersions.clear();
				artifactVersions.add(error);
				return artifactVersions;
			} catch (TimeoutException e) {
				e.printStackTrace();
				String indexUpdate = "Updating Maven Repository Index...";
				artifactVersions.clear();
				artifactVersions.add(indexUpdate);
				return artifactVersions;
			}
		}

		if (syncReq.isDone()) {
			final BooleanQuery query = createArtifactQuery(artifactToSearch);

			final ArtifactInfoFilter versionFilter = createVersionFilter("0.0.0",
					ArtifactVersionSearcher.COMPARISON_TYPE_GREATER);
			List<IndexingContext> contexts = new ArrayList<>();
			contexts.addAll(indexingContexts.values());
			final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, versionFilter);

			final CompletableFuture<List<ArtifactInfo>> getArtifactVersions = CompletableFuture.supplyAsync(() -> {
				IteratorSearchResponse response = null;
				try {
					response = indexer.searchIterator(request);
				} catch (IOException e) {
					// TODO: Use String.join() below to pretty print artifactToSearch
					System.out.println("Index search failed for " + artifactToSearch.getGroupId() + ":"
							+ artifactToSearch.getArtifactId() + ":" + artifactToSearch.getVersion());
					e.printStackTrace();
				}
				List<ArtifactInfo> artifactInfos = new ArrayList<>();
				response.getResults().forEach(ai -> artifactInfos.add(ai));
				return artifactInfos;
			});

			CompletableFuture<Void> addArtifactVersions = getArtifactVersions.thenAccept(artifactInfos -> {
				artifactVersions.clear();
				if (!artifactInfos.isEmpty()) {
					artifactInfos.forEach(info -> artifactVersions.add(info.getVersion()));
				} else {
					String noResults = "No artifact versions found.";
					artifactVersions.add(noResults);
				}
			});
			try {
				addArtifactVersions.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}

		return artifactVersions;
	}

	private ArtifactInfoFilter createVersionFilter(String versionToCompare, int comparisonType) {
		final GenericVersionScheme versionScheme = new GenericVersionScheme();

		return (ctx, ai) -> {
			try {
				final Version aiV = versionScheme.parseVersion(ai.getVersion());
				final Version version = versionScheme.parseVersion(versionToCompare);
				int comparisonResult = aiV.compareTo(version);

				switch (comparisonType) {
				case (ArtifactVersionSearcher.COMPARISON_TYPE_EQUALS):
					return comparisonResult == 0;
				case (ArtifactVersionSearcher.COMPARISON_TYPE_GREATER):
					return comparisonResult >= 0;
				case (ArtifactVersionSearcher.COMPARISON_TYPE_LESS):
					return comparisonResult <= 0;
				default:
					// should never get here
					throw new IllegalArgumentException(
							"comparisonType argument must be one of COMPARISON_TYPE_EQUALS COMPARISON_TYPE_GREATER COMPARISON_TYPE_LESS");
				}

			} catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
				e.printStackTrace();
				return false;
			}
		};
	}

	// TODO: Name this better based on the type of query it returns
	private BooleanQuery createArtifactQuery(Artifact artifactToSearch) {
		// TODO: use SearchType search expression to not bring in new deps

		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.EXACT);

		final Query artifactIdQ = indexer.constructQuery(MAVEN.ARTIFACT_ID, artifactToSearch.getArtifactId(),
				SearchType.EXACT);

		return new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(artifactIdQ, Occur.MUST)
				.add(indexer.constructQuery(MAVEN.PACKAGING, "jar", SearchType.EXACT), Occur.MUST)
				// Note: this below is unfinished API, needs fixing
				.add(indexer.constructQuery(MAVEN.CLASSIFIER, Field.NOT_PRESENT, SearchType.EXACT), Occur.MUST_NOT)
				.build();
	}

	public CompletableFuture<Void> syncIndex() {

		try {
			boolean contextInitialized = true;
			for (RemoteRepository repo : MavenRepositoryCache.getInstance().getRemoteRepositories()) {
				if (!indexingContexts.containsKey(repo.getId()) && !brokenContexts.contains(repo.getId())) {
					contextInitialized = false;
				}
			}

			if (!contextInitialized) {
				initializeContext();
				// TODO: Index should be updated if context isin't initialized, as a new
				// repository might have been added
			}
			return updateIndex();

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

	}

	// TODO: Remove print statements? Maybe send notices over JSON RPC?
	TransferListener listener = new AbstractTransferListener() {
		public void transferStarted(TransferEvent transferEvent) {
			System.out.print("  Downloading " + transferEvent.getResource().getName() + "\n");
		}

		public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
		}

		public void transferCompleted(TransferEvent transferEvent) {
			System.out.println("Done downloading " + transferEvent.getResource().getName());
		}
	};

	private CompletableFuture<Void> updateIndex() throws IOException {
		System.out.println("Updating Index...");
		ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, null, null);

		indexingContexts.keySet().removeIf(repoID -> brokenContexts.contains(repoID));
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (Entry<String, IndexingContext> context : indexingContexts.entrySet()) {
			Date contextCurrentTimestamp = context.getValue().getTimestamp();
			IndexUpdateRequest updateRequest = new IndexUpdateRequest(context.getValue(), resourceFetcher);
			final CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
			});
			try {
				IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
				if (updateResult.isFullUpdate()) {
					System.out.println("Full update happened!");
				} else if (updateResult.getTimestamp().equals(contextCurrentTimestamp)) {
					System.out.println("No update needed, index is up to date!");
				} else {
					System.out.println("Incremental update happened, change covered " + contextCurrentTimestamp + " - "
							+ updateResult.getTimestamp() + " period.");
				}
			} catch (Exception e) {
				brokenContexts.add(context.getKey());
				System.out.println("Invalid Context: " + context.getValue().getRepositoryId() + " @ "
						+ context.getValue().getRepositoryUrl());
				// TODO: Maybe scan for maven metadata to use as an alternative to retrieve GAV
			}
			futures.add(future);
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
	}

	private void initializeContext()
			throws ComponentLookupException, IOException, ExistingLuceneIndexMismatchException {
		File targetDirectory = new File(targetPath);
		if (!targetDirectory.exists()) {
			targetDirectory.mkdirs();
		}

		for (RemoteRepository repo : MavenRepositoryCache.getInstance().getRemoteRepositories()) {
			File repoFile = new File(targetPath + repo.getId() + "-cache");
			File repoIndex = new File(targetPath + repo.getId() + "-index");
			try {
				IndexingContext context = indexer.createIndexingContext(repo.getId() + "-context", repo.getId(),
						repoFile, repoIndex, repo.getUrl(), null, true, true, indexers);
				indexingContexts.put(repo.getId(), context);
			} catch (Exception e) {
				e.printStackTrace();
			}

		}

	}

}
