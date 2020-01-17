package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.ArtifactInfoGroup;
import org.apache.maven.index.Field;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.GroupedSearchRequest;
import org.apache.maven.index.GroupedSearchResponse;
import org.apache.maven.index.Grouping;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.expr.UserInputSearchExpression;
import org.apache.maven.index.search.grouping.GAGrouping;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.Version;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.extensions.maven.MavenPlugin;
import org.eclipse.lsp4xml.extensions.maven.MavenProjectCache;
import org.eclipse.lsp4xml.extensions.maven.RepositoryCache;

public class ArtifactVersionSearcher {
	private static final ArtifactVersionSearcher INSTANCE = new ArtifactVersionSearcher();
	RepositorySystem repositorySystem;
	private DefaultRepositorySystemSession session;
	LocalRepositoryManager localRepoMan;
	private Indexer indexer;

	private IndexUpdater indexUpdater;

	private Wagon httpWagon;

	private IndexingContext centralContext;
	
	private HashSet<IndexingContext> indexingContexts = new HashSet<>();

	private ArtifactVersionSearcher() {
		this.httpWagon = null;
		this.indexUpdater = null;
		this.indexer = null;
		repositorySystem = newRepositorySystem();
		// TODO: get the repo system session from MavenProjectCache?
		session = newRepositorySystemSession(repositorySystem, MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH);
		ArtifactRepository repo = new LocalRepository(new File(MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH));
		localRepoMan = repositorySystem.newLocalRepositoryManager(session, (LocalRepository) repo);
		session.setLocalRepositoryManager(localRepoMan);
	}

	public static ArtifactVersionSearcher getInstance() {
		return INSTANCE;
	}

	// TODO: Move to RepositoryCache
	public Settings getSettings() {
		File settingsFile = new File("/usr/share/maven/conf/settings.xml");
		if (settingsFile.exists()) {
			try {
				Settings settings = new SettingsXpp3Reader().read(new FileReader(settingsFile));
				return settings;
			} catch (IOException | XmlPullParserException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
		return null;

	}

	private static Artifact parseArtifact(DOMNode node) {
		String groupId = null;
		String artifactId = null;
		String version = "0.0.0";
		String type = "jar"; // Default type is jar if no type is specified
		try {
			for (DOMNode tag : node.getParentElement().getChildren()) {
				switch (tag.getLocalName()) {
				case "groupId":
					groupId = tag.getChild(0).getNodeValue();
					break;
				case "artifactId":
					artifactId = tag.getChild(0).getNodeValue();
					break;
				case "version":
					// version = tag.getChild(0).getNodeValue();
					break;
				case "type":
					type = tag.getChild(0).getNodeValue();
					break;
				}
			}
		} catch (IndexOutOfBoundsException e) {
			e.printStackTrace();
		}

		return new DefaultArtifact(groupId, artifactId, type, version);
	}

	// From
	// https://github.com/liferay/liferay-blade-cli/blob/28556e7e8560dd27d4a5153cb93196ca059ac081/com.liferay.blade.cli/src/com/liferay/blade/cli/aether/AetherClient.java#L73
	public String getHighestArtifactVersion(DOMNode node) {
		final VersionRangeRequest rangeRequest = createVersionRangeRequest(node);
		Version version = null;
		try {
			version = repositorySystem.resolveVersionRange(session, rangeRequest).getHighestVersion();
		} catch (Exception e) {
			e.printStackTrace();
			// TODO: handle exception
		}

		if (version == null) {
			return null;
		} else {
			return version.toString();
		}
	}

	public List<String> getArtifactVersions(DOMNode node) {
		final VersionRangeRequest rangeRequest = createVersionRangeRequest(node);
		List<Version> versions = null;
		try {
			versions = repositorySystem.resolveVersionRange(session, rangeRequest).getVersions();
		} catch (Exception e) {
			e.printStackTrace();
			// TODO: handle exception
		}

		if (versions == null) {
			return null;
		} else {
			return versions.stream().map(v -> v.toString()).collect(Collectors.toList());
		}
	}

	public List<String> getArtifactVersionsNew(DOMNode node) throws org.eclipse.aether.version.InvalidVersionSpecificationException {
		List<String> artifactVersions = new ArrayList<>();
		Artifact artifactToSearch = parseArtifact(node);
		
		final GenericVersionScheme versionScheme = new GenericVersionScheme();
		final String versionString = "0.0.0";
		final Version	version = versionScheme.parseVersion(versionString);

		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID,
				new SourcedSearchExpression(artifactToSearch.getGroupId()));
		final Query artifactIdQ = indexer.constructQuery(MAVEN.ARTIFACT_ID,
				new SourcedSearchExpression(artifactToSearch.getArtifactId()));

		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(artifactIdQ, Occur.MUST)
				// we want "jar" artifacts only
				.add(indexer.constructQuery(MAVEN.PACKAGING, new SourcedSearchExpression("jar")), Occur.MUST)
				// we want main artifacts only (no classifier)
				// Note: this below is unfinished API, needs fixing
				.add(indexer.constructQuery(MAVEN.CLASSIFIER, new SourcedSearchExpression(Field.NOT_PRESENT)),
						Occur.MUST_NOT)
				.build();
		
		// construct the filter to express "V greater than"
		final ArtifactInfoFilter versionFilter = new ArtifactInfoFilter() {
			public boolean accepts(final IndexingContext ctx, final ArtifactInfo ai) {
				try {
					final Version aiV = versionScheme.parseVersion(ai.getVersion());
					// Use ">=" if you are INCLUSIVE
					return aiV.compareTo(version) >= 0;
				} catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
					// do something here? be safe and include?
					return true;
				}
			}
		};
		List<IndexingContext> contexts = new ArrayList<>();
		contexts.addAll(indexingContexts);
		final IteratorSearchRequest request = new IteratorSearchRequest(query,
				contexts, versionFilter);
		IteratorSearchResponse response = null;
		try {
			response = indexer.searchIterator(request);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (response != null) {
			for (ArtifactInfo ai : response) {
				artifactVersions.add(ai.getVersion());
				System.out.println(ai.toString());
			}
		}
		
		return artifactVersions;
	}

	private VersionRangeRequest createVersionRangeRequest(DOMNode node) {
		Artifact artifactToSearch = parseArtifact(node);
		artifactToSearch.setVersion("[0,)");
		final VersionRangeRequest rangeRequest = new VersionRangeRequest();
		List<RemoteRepository> repos = new ArrayList<>();
		repos.addAll(RepositoryCache.getInstance().getRemoteRepositories());
		rangeRequest.setArtifact(artifactToSearch);
		rangeRequest.setRepositories(repos);
		return rangeRequest;
	}

	private static RepositorySystem newRepositorySystem() {
		DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(TransporterFactory.class, FileTransporterFactory.class);
		locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

		DefaultServiceLocator.ErrorHandler handler = new DefaultServiceLocator.ErrorHandler() {
			public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
				exception.printStackTrace();
			}
		};

		locator.setErrorHandler(handler);
		RepositorySystem system = locator.getService(RepositorySystem.class);
		return system;
	}

	private static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system,
			String localRepositoryPath) {
		final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		final LocalRepository localRepo = new LocalRepository(localRepositoryPath);
		session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_DAILY);
		session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
		return session;
	}

	public void initialize(MavenProjectCache cache, DOMDocument doc) {
		try {
			repositorySystem = cache.getPlexusContainer().lookup(org.eclipse.aether.RepositorySystem.class);
			// perform(cache);
			initializeContext(cache);
			updateIndex();
		} catch (Exception e) {
			e.printStackTrace();

			// TODO: handle exception
		}

	}

	public void perform(MavenProjectCache cache)
			throws IOException, ComponentLookupException, InvalidVersionSpecificationException,
			org.eclipse.aether.version.InvalidVersionSpecificationException, PlexusContainerException {

		initializeContext(cache);
//
		// Update the index (incremental update will happen if this is not 1st run and
		// files are not deleted)
		// This whole block below should not be executed on every app start, but rather
		// controlled by some configuration
		// since this block will always emit at least one HTTP GET. Central indexes are
		// updated once a week, but
		// other index sources might have different index publishing frequency.
		// Preferred frequency is once a week.
		if (true) {
			updateIndex();
		}

		System.out.println();
		System.out.println("Using index");
		System.out.println("===========");
		System.out.println();

		// ====
		// Case:
		// Search for all GAVs with known G and A and having version greater than V

		final GenericVersionScheme versionScheme = new GenericVersionScheme();
		final String versionString = "1.5.0";
		final Version version = versionScheme.parseVersion(versionString);

		// construct the query for known GA
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID,
				new SourcedSearchExpression("org.sonatype.nexus"));
		final Query artifactIdQ = indexer.constructQuery(MAVEN.ARTIFACT_ID, new SourcedSearchExpression("nexus-api"));

		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(artifactIdQ, Occur.MUST)
				// we want "jar" artifacts only
				.add(indexer.constructQuery(MAVEN.PACKAGING, new SourcedSearchExpression("jar")), Occur.MUST)
				// we want main artifacts only (no classifier)
				// Note: this below is unfinished API, needs fixing
				.add(indexer.constructQuery(MAVEN.CLASSIFIER, new SourcedSearchExpression(Field.NOT_PRESENT)),
						Occur.MUST_NOT)
				.build();

		// construct the filter to express "V greater than"
		final ArtifactInfoFilter versionFilter = new ArtifactInfoFilter() {
			public boolean accepts(final IndexingContext ctx, final ArtifactInfo ai) {
				try {
					final Version aiV = versionScheme.parseVersion(ai.getVersion());
					// Use ">=" if you are INCLUSIVE
					return aiV.compareTo(version) > 0;
				} catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
					// do something here? be safe and include?
					return true;
				}
			}
		};

		System.out.println(
				"Searching for all GAVs with G=org.sonatype.nexus and nexus-api and having V greater than 1.5.0");

		final IteratorSearchRequest request = new IteratorSearchRequest(query,
				Collections.singletonList(centralContext), versionFilter);
		final IteratorSearchResponse response = indexer.searchIterator(request);
		for (ArtifactInfo ai : response) {
			System.out.println(ai.toString());
		}

		// Case:
		// Use index
		// Searching for some artifact
		Query gidQ = indexer.constructQuery(MAVEN.GROUP_ID, new SourcedSearchExpression("org.apache.maven.indexer"));
		Query aidQ = indexer.constructQuery(MAVEN.ARTIFACT_ID, new SourcedSearchExpression("indexer-artifact"));

		BooleanQuery bq = new BooleanQuery.Builder().add(gidQ, Occur.MUST).add(aidQ, Occur.MUST).build();

		searchAndDump(indexer, "all artifacts under GA org.apache.maven.indexer:indexer-artifact", bq);

		// Searching for some main artifact
		bq = new BooleanQuery.Builder().add(gidQ, Occur.MUST).add(aidQ, Occur.MUST)
//	                .add( indexer.constructQuery( MAVEN.CLASSIFIER, new SourcedSearchExpression( "*" ) ), Occur.MUST_NOT )
				.build();

		searchAndDump(indexer, "main artifacts under GA org.apache.maven.indexer:indexer-artifact", bq);

		// doing sha1 search
		searchAndDump(indexer, "SHA1 7ab67e6b20e5332a7fb4fdf2f019aec4275846c2", indexer.constructQuery(MAVEN.SHA1,
				new SourcedSearchExpression("7ab67e6b20e5332a7fb4fdf2f019aec4275846c2")));

		searchAndDump(indexer, "SHA1 7ab67e6b20 (partial hash)",
				indexer.constructQuery(MAVEN.SHA1, new UserInputSearchExpression("7ab67e6b20")));

		// doing classname search (incomplete classname)
		searchAndDump(indexer, "classname DefaultNexusIndexer (note: Central does not publish classes in the index)",
				indexer.constructQuery(MAVEN.CLASSNAMES, new UserInputSearchExpression("DefaultNexusIndexer")));

		// doing search for all "canonical" maven plugins latest versions
		bq = new BooleanQuery.Builder()
				.add(indexer.constructQuery(MAVEN.PACKAGING, new SourcedSearchExpression("maven-plugin")), Occur.MUST)
				.add(indexer.constructQuery(MAVEN.GROUP_ID, new SourcedSearchExpression("org.apache.maven.plugins")),
						Occur.MUST)
				.build();

		searchGroupedAndDump(indexer, "all \"canonical\" maven plugins", bq, new GAGrouping());

		// doing search for all archetypes latest versions
		searchGroupedAndDump(indexer, "all maven archetypes (latest versions)",
				indexer.constructQuery(MAVEN.PACKAGING, new SourcedSearchExpression("maven-archetype")),
				new GAGrouping());

		// close cleanly
		indexer.closeIndexingContext(centralContext, false);
	}

	private void updateIndex() throws IOException {
		System.out.println("Updating Index...");
		System.out.println("This might take a while on first run, so please be patient!");
		// Create ResourceFetcher implementation to be used with IndexUpdateRequest
		// Here, we use Wagon based one as shorthand, but all we need is a
		// ResourceFetcher implementation
		TransferListener listener = new AbstractTransferListener() {
			public void transferStarted(TransferEvent transferEvent) {
				System.out.print("  Downloading " + transferEvent.getResource().getName());
			}

			public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
				System.out.print(".");
			}

			public void transferCompleted(TransferEvent transferEvent) {
				System.out.println("\n - Done");
			}
		};
		ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, null, null);
		for (IndexingContext context : indexingContexts) {
			Date contextCurrentTimestamp = context.getTimestamp();
			IndexUpdateRequest updateRequest = new IndexUpdateRequest(context, resourceFetcher);
			IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
			if (updateResult.isFullUpdate()) {
				System.out.println("Full update happened!");
			} else if (updateResult.getTimestamp().equals(contextCurrentTimestamp)) {
				System.out.println("No update needed, index is up to date!");
			} else {
				System.out.println("Incremental update happened, change covered " + contextCurrentTimestamp + " - "
						+ updateResult.getTimestamp() + " period.");
			}

			System.out.println();
		}

	}

	private void initializeContext(MavenProjectCache cache)
			throws ComponentLookupException, IOException, ExistingLuceneIndexMismatchException {

		// Files where local cache is (if any) and Lucene Index should be located
		// TODO: get location of Target directory relative to parent pom
		final String root = "/home/aobuchow/git/lsp4xml-extensions-maven/org.eclipse.lsp4xml.extensions.maven/target/";
		File centralLocalCache = new File(
				"/home/aobuchow/git/lsp4xml-extensions-maven/org.eclipse.lsp4xml.extensions.maven/target/central-cache");
		File centralIndexDir = new File(
				"/home/aobuchow/git/lsp4xml-extensions-maven/org.eclipse.lsp4xml.extensions.maven/target/entral-index");
		PlexusContainer plexusContainer = cache.getPlexusContainer();
		indexer = plexusContainer.lookup(Indexer.class);
		indexUpdater = plexusContainer.lookup(IndexUpdater.class);
		// lookup wagon used to remotely fetch index
		httpWagon = plexusContainer.lookup(Wagon.class, "http");

		// Creators we want to use (search for fields it defines)
		List<IndexCreator> indexers = new ArrayList<>();
		indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
		indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
		indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));

		// Create context for central repository index
		centralContext = indexer.createIndexingContext("central-context", "central", centralLocalCache, centralIndexDir,
				"https://repo1.maven.org/maven2", null, true, true, indexers);
		
		
		for (RemoteRepository repo : RepositoryCache.getInstance().getRemoteRepositories()) {
			File repoFile = new File(root + repo.getId() + "-cache");
			File repoIndex = new File(root + repo.getId() + "-index");
			indexingContexts.add(indexer.createIndexingContext(repo.getId() + "-context", repo.getId(), repoFile, repoIndex, repo.getUrl(), null, true, true, indexers));
		}
		
		indexingContexts.add(centralContext);
	}

	public void searchAndDump(Indexer nexusIndexer, String descr, Query q) throws IOException {
		System.out.println("Searching for " + descr);

		FlatSearchResponse response = nexusIndexer.searchFlat(new FlatSearchRequest(q, centralContext));

		for (ArtifactInfo ai : response.getResults()) {
			System.out.println(ai.toString());
		}

		System.out.println("------");
		System.out.println("Total: " + response.getTotalHitsCount());
		System.out.println();
	}

	private static final int MAX_WIDTH = 60;

	public void searchGroupedAndDump(Indexer nexusIndexer, String descr, Query q, Grouping g) throws IOException {
		System.out.println("Searching for " + descr);

		GroupedSearchResponse response = nexusIndexer.searchGrouped(new GroupedSearchRequest(q, g, centralContext));

		for (Map.Entry<String, ArtifactInfoGroup> entry : response.getResults().entrySet()) {
			ArtifactInfo ai = entry.getValue().getArtifactInfos().iterator().next();
			System.out.println("* Entry " + ai);
			System.out.println("  Latest version:  " + ai.getVersion());
			// System.out.println(StringUtils.isBlank(ai.getDescription()) ? "No description
			// in plugin's POM."
			// : StringUtils.abbreviate(ai.getDescription(), MAX_WIDTH));
			System.out.println();
		}

		System.out.println("------");
		System.out.println("Total record hits: " + response.getTotalHitsCount());
		System.out.println();
	}

}
