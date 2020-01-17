package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.DefaultRepositoryRequest;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.version.Version;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.extensions.maven.MavenProjectCache;
import org.eclipse.lsp4xml.extensions.maven.RepositoryCache;

public class ArtifactVersionSearcher2 {
	private static final ArtifactVersionSearcher2 INSTANCE = new ArtifactVersionSearcher2();
	org.apache.maven.repository.RepositorySystem repositorySystem; 
	private DefaultRepositorySystemSession session;
	LocalRepositoryManager localRepoMan;

	private ArtifactVersionSearcher2() {
		//repositorySystem = newRepositorySystem();

		// TODOrequest: get the repo system session from MavenProjectCache?
		//session = newRepositorySystemSession(repositorySystem, MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH);
		//ArtifactRepository repo = new LocalRepository(new File(MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH));
		//localRepoMan = repositorySystem.newLocalRepositoryManager(session, (LocalRepository) repo);

	}

	public static ArtifactVersionSearcher2 getInstance() {
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
		String version = null;
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
		//TODO: placeholder, fix
		return new DefaultArtifact(groupId, "junit", "[0,)", "compile", "jar", null, new DefaultArtifactHandler("jar"));
	}

	// From
	// https://github.com/liferay/liferay-blade-cli/blob/28556e7e8560dd27d4a5153cb93196ca059ac081/com.liferay.blade.cli/src/com/liferay/blade/cli/aether/AetherClient.java#L73
	public String getHighestArtifactVersion(DOMNode node) {
		final VersionRangeRequest rangeRequest = createVersionRangeRequest(node);
		Version version = null;
		try {
			//version = repositorySystem.resolveVersionRange(session, rangeRequest).getHighestVersion();
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
			//versions = repositorySystem.resolveVersionRange(session, rangeRequest).getVersions();
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

	private VersionRangeRequest createVersionRangeRequest(DOMNode node) {
		Artifact artifactToSearch = parseArtifact(node);
		artifactToSearch.setVersion("[0,)");
		final VersionRangeRequest rangeRequest = new VersionRangeRequest();
		List<RemoteRepository> repos = new ArrayList<>();
		repos.addAll(RepositoryCache.getInstance().getRemoteRepositories());
		//rangeRequest.setArtifact(artifactToSearch);
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

	public void Initialize(MavenProjectCache cache, DOMDocument document) {
		this.session = cache.getRepositorySystemSession();
		this.repositorySystem = cache.getRepositorySystem();
		MavenArtifactRepository ar = (MavenArtifactRepository) repositorySystem.createArtifactRepository("central", "https://repo1.maven.org/maven2/", new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));
		MavenRepositorySystem mrs = cache.getMavenRepositorySystem();
		ArtifactRepository artifactRepo = null;
		try {
			
			artifactRepo = mrs.createDefaultRemoteRepository(new DefaultMavenExecutionRequest().addRemoteRepository(ar));
			MavenProject project=  cache.getLastSuccessfulMavenProject(document);
			List<ArtifactRepository> remoteRepos = new ArrayList<>();
			remoteRepos.add(artifactRepo);
			project.setRemoteArtifactRepositories(remoteRepos);
			ArtifactResolutionRequest req = new ArtifactResolutionRequest();
			req.setArtifact(new DefaultArtifact("junit", "junit", "RELEASE", "compile", "jar", null, new DefaultArtifactHandler("jar")));
			req.setRemoteRepositories(remoteRepos);
			req.setForceUpdate(true);
			req.setLocalRepository(cache.getLocalRepository());
			 ArtifactResolutionResult result = repositorySystem.resolve(req);
			 repositorySystem.retrieve(repository, destination, remotePath, transferListener);
			artifactRepo.find(new DefaultArtifact("junit", "junit", "4.13", "compile", "jar", null, new DefaultArtifactHandler("jar"))).getAvailableVersions();
			artifactRepo.findVersions(new DefaultArtifact("junit", "junit", "4.13", "compile", "jar", null, new DefaultArtifactHandler("jar")));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		DefaultRepositoryRequest reporeq = new DefaultRepositoryRequest();
		reporeq.setForceUpdate(true);
		remoteRepos.add(artifactRepo);
		reporeq.
		//remoteRepos.add(new ArtifactRepositoryFactory())
		reporeq.setRemoteRepositories(remoteRepos);
		ar.findVersions(new DefaultArtifact("junit", "junit", "[0,)", "compile", "jar", null, new DefaultArtifactHandler("jar")));
		session.setLocalRepositoryManager(localRepoMan);
		// TODO Auto-generated method stub
		
	}

}
