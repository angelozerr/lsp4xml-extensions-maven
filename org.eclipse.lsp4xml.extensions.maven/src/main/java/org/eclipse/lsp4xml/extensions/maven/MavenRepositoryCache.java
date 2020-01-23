package org.eclipse.lsp4xml.extensions.maven;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.repository.RemoteRepository;

public class MavenRepositoryCache {
	HashSet<RemoteRepository> repos = new HashSet<>();

	private static final MavenRepositoryCache INSTANCE = new MavenRepositoryCache();

	private MavenRepositoryCache() {
		repos.add(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()); // Default
																													// maven
																													// repository
	}

	// TODO: get user settings
	public Settings getSettings() {
		File globalSettings = new File("/usr/share/maven/conf/settings.xml");
		if (globalSettings.exists()) {
			try {
				Settings settings = new SettingsXpp3Reader().read(new FileReader(globalSettings));
				return settings;
			} catch (IOException | XmlPullParserException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	public void update(MavenProject project) {
		if (project == null || project.getModel() == null) {
			return;

		}
		Model model = project.getModel();
		if (model != null) {
			repos.addAll(model.getRepositories().stream()
					.map(repo -> new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl()).build())
					.distinct().collect(Collectors.toList()));
			Deque<MavenProject> parentHierarchy = new ArrayDeque<>();
			if (model.getParent() != null) {
				parentHierarchy.add(project.getParent());
				while (!parentHierarchy.isEmpty()) {
					MavenProject currentParentProj = parentHierarchy.pop();
					if (currentParentProj.getParent() != null) {
						Model parentModel = currentParentProj.getParent().getModel();
						repos.addAll(parentModel.getRepositories().stream()
								.map(repo -> new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl())
										.build())
								.distinct().collect(Collectors.toList()));
						parentHierarchy.add(currentParentProj.getParent());
					}
				}
			}
		}

		// TODO: get repositories from maven user and global settings.xml

		// Remove repositories with duplicate id
		HashSet<String> seen = new HashSet<>();
		repos.removeIf(repo -> !seen.add(repo.getId()));

	}

	public List<RemoteRepository> getRemoteRepositories() {
		return this.repos.stream().collect(Collectors.toList());
	}

	public static MavenRepositoryCache getInstance() {
		return INSTANCE;
	}

}
