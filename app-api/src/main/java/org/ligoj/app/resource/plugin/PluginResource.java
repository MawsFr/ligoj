/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.resource.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.ligoj.app.api.FeaturePlugin;
import org.ligoj.app.api.ServicePlugin;
import org.ligoj.app.api.SubscriptionMode;
import org.ligoj.app.api.ToolPlugin;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.PluginRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Plugin;
import org.ligoj.app.model.PluginType;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.plugin.repository.Artifact;
import org.ligoj.app.resource.plugin.repository.EmptyRepositoryManager;
import org.ligoj.app.resource.plugin.repository.RepositoryManager;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.dao.csv.CsvForJpa;
import org.ligoj.bootstrap.core.model.AbstractBusinessEntity;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.context.restart.RestartEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Persistable;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Manage plug-in life-cycle.
 *
 * @see <a href="https://repository.sonatype.org/nexus-indexer-lucene-plugin/default/docs/path__lucene_search.html">OSS
 *      lucene_search</a>
 */
@Path("/system/plugin")
@Slf4j
@Component
@Transactional
@Produces(MediaType.APPLICATION_JSON)
public class PluginResource {

	private static final String REPO_CENTRAL = "central";

	/**
	 * Property identifying an array of plug-ins to ignore.
	 */
	private static final String PLUGIN_IGNORE = "ligoj.plugin.ignore";

	/**
	 * Plug-ins auto update flag.
	 */
	private static final String PLUGIN_UPDATE = "ligoj.plugin.update";

	/**
	 * Plug-ins repository used for auto-update mode.
	 */
	private static final String PLUGIN_REPOSITORY = "ligoj.plugin.repository";

	private static final RepositoryManager EMPTY_REPOSITORY = new EmptyRepositoryManager();

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	protected CacheManager cacheManager;

	@Autowired
	private PluginRepository repository;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	protected CsvForJpa csvForJpa;

	@Autowired
	protected EntityManager em;

	@Autowired
	private RestartEndpoint restartEndpoint;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ConfigurationResource configuration;

	/**
	 * Return all plug-ins with details.
	 *
	 * @param repository
	 *            The repository identifier to query.
	 * @return All plug-ins with details.
	 * @throws IOException
	 *             When the last version index file cannot be be retrieved.
	 */
	@GET
	public List<PluginVo> findAll(@QueryParam("repository") @DefaultValue(REPO_CENTRAL) final String repository) throws IOException {
		// Get the available plug-ins
		final Map<String, Artifact> lastVersion = getLastPluginVersions(repository);
		final Map<String, FeaturePlugin> enabledFeatures = context.getBeansOfType(FeaturePlugin.class);

		// Get the enabled plug-in features
		final Map<String, PluginVo> enabled = this.repository.findAll().stream()
				.map(p -> toVo(lastVersion, p, enabledFeatures.values().stream().filter(f -> p.getKey().equals(f.getKey())).findFirst().orElse(null)))
				.filter(Objects::nonNull).collect(Collectors.toMap(p -> p.getPlugin().getArtifact(), Function.identity()));

		// Add pending installation: available but not yet enabled plug-ins
		getPluginClassLoader().getInstalledPlugins().entrySet().forEach(i -> {
			enabled.computeIfPresent(i.getKey(), (k, p) -> {
				// Check if it's an update
				if (!p.getPlugin().getVersion().equals(toTrimmedVersion(i.getValue()))) {
					// Corresponds to a different version
					p.setLatestLocalVersion(toTrimmedVersion(i.getValue()));
				}
				p.setDeleted(isDeleted(p));
				return p;
			});

			// Add new plug-ins
			enabled.computeIfAbsent(i.getKey(), k -> {
				final Plugin plugin = new Plugin();
				plugin.setArtifact(k);
				plugin.setKey("?:" + Arrays.stream(k.split("-")).skip(1).collect(Collectors.joining("-")));

				final PluginVo p = new PluginVo();
				p.setId(k);
				p.setName(k);
				p.setPlugin(plugin);
				p.setLatestLocalVersion(toTrimmedVersion(i.getValue()));
				return p;
			});
		});

		//
		return enabled.values().stream().sorted(Comparator.comparing(NamedBean::getId)).collect(Collectors.toList());
	}

	/**
	 * Indicate the plug-in is deleted or not.
	 * @param plugin
	 * @return <true> when the plug-in is deleted locally from the FS.
	 */
	protected boolean isDeleted(final PluginVo plugin) {
		return !new File(plugin.getLocation()).exists();
	}

	/**
	 * Convert an extended version to a trim one. Example:
	 * <ul>
	 * <li><code>plugin-sample-Z0000001Z0000002Z0000003Z0000004</code> will be <code>1.2.3.4</code></li>
	 * <li><code>plugin-sample-Z0000001Z0000002Z0000003Z0000000</code> will be <code>1.2.3</code></li>
	 * <li><code>plugin-sample-Z0000001Z0000002Z0000003SNAPSHOT</code> will be <code>1.2.3-SNAPSHOT</code></li>
	 * <li><code>plugin-sample-1.2.3.4</code> will be <code>1.2.3.4</code></li>
	 * <li><code>1.2.3.4</code> will be <code>1.2.3.4</code></li>
	 * <li><code>1.2.3.4</code> will be <code>1.2.3.4</code></li>
	 * <li><code>1.2.3.0</code> will be <code>1.2.3</code></li>
	 * <li><code>0.1.2.3</code> will be <code>0.1.2.3</code></li>
	 * </ul>
	 *
	 * @param extendedVersion
	 *            The extended version. Trim version is also accepted.
	 * @return Trim version.
	 */
	protected String toTrimmedVersion(final String extendedVersion) {
		String trim = Arrays.stream(StringUtils.split(extendedVersion, "-Z.")).dropWhile(s -> !s.matches("^(Z?\\d+.*)"))
				.map(s -> StringUtils.defaultIfBlank(StringUtils.replaceFirst(s, "^0+", ""), "0")).collect(Collectors.joining("."))
				.replace(".SNAPSHOT", "-SNAPSHOT").replaceFirst("([^-])SNAPSHOT", "$1-SNAPSHOT");
		if (trim.endsWith(".0") && StringUtils.countMatches(trim, '.') > 2) {
			trim = StringUtils.removeEnd(trim, ".0");
		}
		return trim;
	}

	/**
	 * Build the plug-in information from the plug-in itself and the last version being available.
	 */
	private PluginVo toVo(final Map<String, Artifact> lastVersion, final Plugin p, final FeaturePlugin feature) {
		if (feature == null) {
			// Plug-in is no more available or in fail-safe mode
			return null;
		}

		// Plug-in implementation is available
		final String key = p.getKey();
		final PluginVo vo = new PluginVo();
		vo.setId(p.getKey());
		vo.setName(StringUtils.removeStart(feature.getName(), "Ligoj - Plugin "));
		vo.setLocation(getPluginLocation(feature).getPath());
		vo.setVendor(feature.getVendor());
		vo.setPlugin(p);

		// Expose the resolve newer version
		vo.setNewVersion(Optional.ofNullable(lastVersion.get(p.getArtifact())).map(Artifact::getVersion)
				.filter(v -> PluginsClassLoader.toExtendedVersion(v).compareTo(PluginsClassLoader.toExtendedVersion(p.getVersion())) > 0)
				.orElse(null));

		// Node statistics
		if (p.getType() != PluginType.FEATURE) {
			// This is a node (service or tool) add statistics and details
			vo.setNodes(nodeRepository.countByRefined(key));
			vo.setSubscriptions(subscriptionRepository.countByNode(key));
			vo.setNode(NodeResource.toVo(nodeRepository.findOne(key)));
		}
		return vo;
	}

	/**
	 * Search plug-ins in repository which can be installed.
	 *
	 * @param query
	 *            The optional searched term..
	 * @param repository
	 *            The repository identifier to query.
	 * @return All plug-ins artifacts name.
	 * @throws IOException
	 *             When the last version index file cannot be be retrieved.
	 */
	@GET
	@Path("search")
	public List<Artifact> search(@QueryParam("q") @DefaultValue("") final String query,
			@QueryParam("repository") @DefaultValue(REPO_CENTRAL) final String repository) throws IOException {
		return getLastPluginVersions(repository).values().stream().filter(a -> a.getArtifact().contains(query)).collect(Collectors.toList());
	}

	/**
	 * Return the {@link RepositoryManager} with the given identifier.
	 *
	 * @param repository
	 *            The repository identifier.
	 * @return The {@link RepositoryManager} with the given identifier or {@link #EMPTY_REPOSITORY}
	 */
	protected RepositoryManager getRepositoryManager(final String repository) {
		return SpringUtils.getApplicationContext().getBeansOfType(RepositoryManager.class).values().stream().filter(r -> r.getId().equals(repository))
				.findFirst().orElse(EMPTY_REPOSITORY);
	}

	/**
	 * Request a restart of the current application context in a separated thread.
	 */
	@PUT
	@Path("restart")
	public void restart() {
		final Thread restartThread = new Thread(() -> restartEndpoint.restart(), "Restart"); // NOPMD
		restartThread.setDaemon(false);
		restartThread.start();
	}

	/**
	 * Request a reset of plug-in cache meta-data
	 *
	 * @param repository
	 *            The repository identifier to reset.
	 */
	@PUT
	@Path("cache")
	public void invalidateLastPluginVersions(@QueryParam("repository") @DefaultValue(REPO_CENTRAL) final String repository) {
		getRepositoryManager(repository).invalidateLastPluginVersions();
	}

	/**
	 * Remove all versions the specified plug-in and the related (by name) plug-ins.
	 *
	 * @param artifact
	 *            The Maven artifact identifier and also corresponding to the plug-in simple name.
	 * @throws IOException
	 *             When the file cannot be read or deleted from the file system.
	 */
	@DELETE
	@Path("{artifact:[\\w-]+}")
	public void delete(@PathParam("artifact") final String artifact) throws IOException {
		removeFilter(artifact, "(-.*)?");
		log.info("Plugin {} has been deleted, restart is required", artifact);
	}

	/**
	 * Remove the specific version of a plug-in.
	 *
	 * @param artifact
	 *            The Maven artifact identifier and also corresponding to the plug-in simple name.
	 * @param version
	 *            The specific version.
	 * @throws IOException
	 *             When the file cannot be read or deleted from the file system.
	 */
	@DELETE
	@Path("{artifact:[\\w-]+}/{version}")
	public void delete(@PathParam("artifact") final String artifact, @PathParam("version") final String version) throws IOException {
		removeFilter(artifact, "-" + version.replace(".", "\\."));
		log.info("Plugin {} v{} has been deleted, restart is required", artifact, version);
	}

	private void removeFilter(final String artifact, final String filter) throws IOException {
		Files.list(getPluginClassLoader().getPluginDirectory()).filter(p -> p.getFileName().toString().matches("^" + artifact + filter + "\\.jar$"))
				.forEach(p -> p.toFile().delete());
	}

	/**
	 * Install the specific version of given plug-in from the remote server. The previous version is not deleted. The
	 * downloaded version will be used only if it is a most recent version than the locally ones.
	 *
	 * @param artifact
	 *            The Maven artifact identifier and also corresponding to the plug-in simple name.
	 * @param version
	 *            The version to install.
	 * @param repository
	 *            The repository identifier to query.
	 */
	@POST
	@Path("{artifact:[\\w-]+}/{version:[\\w-]+}")
	public void install(@PathParam("artifact") final String artifact, @PathParam("version") final String version,
			@QueryParam("repository") @DefaultValue(REPO_CENTRAL) final String repository) {
		install(null, artifact, version, repository);
	}

	/**
	 * Upload a file of entries to create or update users. The whole entry is replaced.
	 *
	 * @param input
	 *            The Maven artifact file.
	 * @param pluginId
	 *            The Maven <code>artifactId</code>.
	 * @param version
	 *            The Maven <code>version</code>.
	 */
	@PUT
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Path("upload")
	public void upload(@Multipart(required = true, value = "plugin-file") final InputStream input,
			@Multipart(required = true, value = "plugin-id") final String pluginId,
			@Multipart(required = true, value = "plugin-version") final String version) {
		install(input, pluginId, version, "(local)");
	}

	private void install(final InputStream input, final String artifact, final String version, final String repository) {
		final PluginsClassLoader classLoader = getPluginClassLoader();
		final java.nio.file.Path target = classLoader.getPluginDirectory().resolve(artifact + "-" + version + ".jar");
		log.info("Download plug-in {} v{} from {}", artifact, version, repository);
		try {
			// Get the right input
			final InputStream input2 = input == null ? getRepositoryManager(repository).getArtifactInputStream(artifact, version) : input;
			// Download and copy the file, note the previous version is not removed
			Files.copy(input2, target, StandardCopyOption.REPLACE_EXISTING);
			log.info("Plugin {} v{} has been installed, restart is required", artifact, version);
		} catch (final Exception ioe) {
			// Installation failed, either download, either FS error
			log.info("Unable to install plugin {} v{} from {}", artifact, version, repository, ioe);
			throw new BusinessException(artifact, String.format("Cannot be installed %s", artifact), ioe);
		}
	}

	/**
	 * Install or update to the last available version of given plug-in from the remote server.
	 *
	 * @param artifact
	 *            The Maven artifact identifier and also corresponding to the plug-in simple name.
	 * @param repository
	 *            The repository identifier to query.
	 * @throws IOException
	 *             When install failed.
	 */
	@POST
	@Path("{artifact:[\\w-]+}")
	public void install(@PathParam("artifact") final String artifact, @QueryParam("repository") @DefaultValue(REPO_CENTRAL) final String repository)
			throws IOException {
		final Artifact resultItem = getLastPluginVersions(repository).get(artifact);
		if (resultItem == null) {
			// Plug-in not found, or not the last version
			throw new BusinessException(String.format("No latest version found for plug-in %s on repository %s", artifact, repository));
		}
		install(artifact, resultItem.getVersion(), repository);
	}

	private Map<String, Artifact> getLastPluginVersions(final String repository) throws IOException {
		final Map<String, Artifact> versions = getRepositoryManager(repository).getLastPluginVersions();
		Arrays.stream(configuration.get(PLUGIN_IGNORE, "").split(",")).map(String::trim).forEach(versions::remove);
		return versions;
	}

	/**
	 * Return the current plug-in class loader.
	 *
	 * @return The current plug-in class loader.
	 */
	protected PluginsClassLoader getPluginClassLoader() {
		return PluginsClassLoader.getInstance();
	}

	/**
	 * Handle the newly installed plug-ins implementing {@link FeaturePlugin}, and that's includes
	 * {@link ServicePlugin}. Note the plug-ins are installed in a natural order based on their key's name to ensure the
	 * parents plug-ins are configured first. <br>
	 * Note the transactional behavior of this process : if one plug-in failed to be configured, then the entire process
	 * is cancelled. The previously and the not processed discovered plug-ins are not configured.
	 *
	 * @param event
	 *            The Spring event.
	 * @throws Exception
	 *             When the context can not be refreshed because of plugin updates or configurations..
	 */
	@EventListener
	public void refreshPlugins(final ContextRefreshedEvent event) throws Exception {
		// Auto update plug-ins
		if (Boolean.valueOf(configuration.get(PLUGIN_UPDATE, "false"))) {
			// Update the plug-ins
			final int counter = autoUpdate();
			if (counter > 0) {
				log.info("{} plug-ins have been downloaded for update, context will be restarted", counter);
				restart();
				return;
			}
			log.info("No plug-ins have been automatically downloaded for update");
		}

		refreshPlugins(event.getApplicationContext());
	}

	/**
	 * Auto update the installed plug-ins.
	 *
	 * @return The amount of updated plug-ins.
	 * @throws IOException
	 *             When plug-ins cannot be updated.
	 */
	public int autoUpdate() throws IOException {
		final Map<String, String> plugins = getPluginClassLoader().getInstalledPlugins();
		final String repository = configuration.get(PLUGIN_REPOSITORY, REPO_CENTRAL);
		int counter = 0;
		for (final Artifact artifact : getLastPluginVersions(repository).values().stream().filter(a -> plugins.containsKey(a.getArtifact()))
				.filter(a -> PluginsClassLoader.toExtendedVersion(a.getVersion())
						.compareTo(StringUtils.removeStart(plugins.get(a.getArtifact()), a.getArtifact() + "-")) > 0)
				.collect(Collectors.toList())) {
			install(artifact.getArtifact(), repository);
			counter++;
		}
		return counter;
	}

	private void refreshPlugins(final ApplicationContext context) throws Exception {
		// Get the existing plug-in features
		final Map<String, Plugin> plugins = repository.findAll().stream().collect(Collectors.toMap(Plugin::getKey, Function.identity()));

		// Changes, order by the related feature's key
		final Map<String, FeaturePlugin> newFeatures = new TreeMap<>();
		final Map<String, FeaturePlugin> updateFeatures = new TreeMap<>();
		final Set<Plugin> removedPlugins = new HashSet<>(plugins.values());

		// Compare with the available plug-in implementing ServicePlugin
		context.getBeansOfType(FeaturePlugin.class).values().stream().forEach(s -> {
			final Plugin plugin = plugins.get(s.getKey());
			if (plugin == null) {
				// New plug-in case
				newFeatures.put(s.getKey(), s);
			} else {
				// Update the artifactId. May have not changed
				plugin.setArtifact(toArtifactId(s));
				if (!plugin.getVersion().equals(getVersion(s))) {
					// The version is different, consider it as an update
					updateFeatures.put(s.getKey(), s);
				}

				// This plug-in has just been handled, so not removed
				removedPlugins.remove(plugin);
			}
		});

		// First install the data of new plug-ins
		updateFeatures.values().stream().forEach(s -> configurePluginUpdate(s, plugins.get(s.getKey())));
		newFeatures.values().stream().forEach(this::configurePluginInstall);

		// Then install/update the plug-in
		update(updateFeatures, plugins);
		installInternal(newFeatures);
		log.info("Plugins are now configured");

		// And remove the old plug-in no more installed
		repository.deleteAll(removedPlugins.stream().map(Persistable::getId).collect(Collectors.toList()));
	}

	/**
	 * Install all ordered plug-ins.
	 */
	private void installInternal(final Map<String, FeaturePlugin> newFeatures) throws Exception {
		for (final FeaturePlugin feature : newFeatures.values()) {
			// Do not trigger the install event when corresponding node is already there
			if (!nodeRepository.existsById(feature.getKey())) {
				feature.install();
			}
		}
	}

	/**
	 * Update all ordered plug-ins.
	 */
	private void update(final Map<String, FeaturePlugin> updateFeatures, final Map<String, Plugin> plugins) throws Exception {
		for (Entry<String, FeaturePlugin> feature : updateFeatures.entrySet()) {
			feature.getValue().update(plugins.get(feature.getKey()).getVersion());
		}
	}

	/**
	 * Returns a plug-in's last modified time.
	 *
	 * @param plugin
	 *            The plug-in class. Will be used to find the related container archive or class file.
	 * @return a {@code String} representing the time the file was last modified, or a default time stamp to indicate
	 *         the time of last modification is not supported by the file system
	 * @throws URISyntaxException
	 *             if an I/O error occurs
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	protected String getLastModifiedTime(final FeaturePlugin plugin) throws IOException, URISyntaxException {
		return Files.getLastModifiedTime(Paths.get(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())).toString();
	}

	/**
	 * Configure the updated plug-in in this order :
	 * <ul>
	 * <li>The required entities for the plug-in are persisted. These entities are discovered from
	 * {@link FeaturePlugin#getInstalledEntities()} and related CSV files are load in the data base.</li>
	 * <li>The entity {@link Plugin} is updated to reflect the new version.</li>
	 * </ul>
	 *
	 * @param plugin
	 *            The newly updated plug-in.
	 * @param entity
	 *            The current plug-in entity to update.
	 */
	protected void configurePluginUpdate(final FeaturePlugin plugin, final Plugin entity) {
		final String newVersion = getVersion(plugin);
		log.info("Updating the plugin {} v{} -> v{}", plugin.getKey(), entity.getVersion(), newVersion);
		entity.setVersion(newVersion);
	}

	/**
	 * Configure the new plug-in in this order :
	 * <ul>
	 * <li>The required entities for the plug-in are persisted. These entities are discovered from
	 * {@link FeaturePlugin#getInstalledEntities()} and related CSV files are load in the data base. Note that there is
	 * at least one {@link Node} records added and related to the plug-in key.</li>
	 * <li>A new {@link Plugin} is inserted to maintain the validated plug-in and version</li>
	 * </ul>
	 *
	 * @param plugin
	 *            The newly discovered plug-in.
	 */
	protected void configurePluginInstall(final FeaturePlugin plugin) {
		final String newVersion = getVersion(plugin);
		log.info("Installing the new plugin {} v{}", plugin.getKey(), newVersion);
		try {
			// Build and persist the Plugin entity
			final Plugin entity = new Plugin();
			entity.setArtifact(toArtifactId(plugin));
			entity.setKey(plugin.getKey());
			entity.setVersion(newVersion);
			entity.setType(plugin instanceof ServicePlugin ? determinePluginType((ServicePlugin) plugin) : PluginType.FEATURE);
			repository.saveAndFlush(entity);

			// Manage disable then re-enable base with double install
			final List<Class<?>> installedEntities = plugin.getInstalledEntities();
			if (!nodeRepository.existsById(plugin.getKey()) && entity.getType() != PluginType.FEATURE && !installedEntities.contains(Node.class)) {
				// This feature has not previously been installed
				// Persist the partial default node now for the bellow installation process
				nodeRepository.saveAndFlush(newNode((ServicePlugin) plugin));
			}

			// Configure the plug-in entities
			configurePluginEntities(plugin, installedEntities);
		} catch (final Exception e) { // NOSONAR - Catch all to notice every time the failure
			// Something happened
			log.error("Installing the new plugin {} v{} failed", plugin.getKey(), newVersion, e);
			throw new TechnicalException(String.format("Configuring the new plugin %s failed", plugin.getKey()), e);
		}
	}

	/**
	 * Guess the Maven artifactId from plug-in artifact name. Use the key and replace the "service" or "feature" part by
	 * "plugin".
	 *
	 * @param plugin
	 *            The plugin class.
	 * @return The Maven "artifactId" as it should be be when naming convention is respected. Required to detect the new
	 *         version.
	 */
	public String toArtifactId(final FeaturePlugin plugin) {
		return "plugin-" + Arrays.stream(plugin.getKey().split(":")).skip(1).collect(Collectors.joining("-"));
	}

	/**
	 * Get a file reference for a specific subscription. This file will use the
	 * subscription as a context to isolate it, and using the related node and
	 * the subscription's identifier. The parent directory is created as needed.
	 *
	 * @param subscription
	 *            The subscription used a context of the file to create.
	 * @param fragments
	 *            The file fragments.
	 * @return The file reference.
	 * @throws IOException
	 *             When the file creation failed.
	 */
	public File toFile(final Subscription subscription, final String... fragments) throws IOException {
		java.nio.file.Path parent = toPath(getPluginClassLoader().getHomeDirectory(), subscription.getNode());
		parent = parent.resolve(String.valueOf(subscription.getId()));

		// Ensure the t
		for (int i = 0; i < fragments.length; i++) {
			parent = parent.resolve(fragments[i]);
		}
		FileUtils.forceMkdir(parent.getParent().toFile());
		return parent.toFile();
	}

	/**
	 * Convert a {@link Node} to a {@link java.nio.file.Path} inside the given parent
	 * directory.
	 *
	 * @param parent
	 *            The parent path.
	 * @param node
	 *            The related node.
	 * @return The computed sibling path.
	 */
	private java.nio.file.Path toPath(final java.nio.file.Path parent, final Node node) {
		return (node.isRefining() ? toPath(parent, node.getRefined()) : parent).resolve(toFragmentId(node).replace(':', '-'));
	}

	/**
	 * Return the last part of the node identifier, excluding the part of the
	 * parent. Built like that :
	 * <ul>
	 * <li>node = 'service:id:ldap:ad1', fragment = 'ad1'</li>
	 * <li>node = 'service:id:ldap', fragment = 'ldap'</li>
	 * <li>node = 'service:id', fragment = 'service:id'</li>
	 * </ul>
	 *
	 * @param node
	 *            The node to convert to a simple fragment String.
	 * @return The simple fragment.
	 */
	private String toFragmentId(final Node node) {
		return node.isRefining() ? node.getId().substring(node.getRefined().getId().length() + 1) : node.getId();
	}

	/**
	 * Determine the plug-in type and check it regarding the contact and the convention.
	 *
	 * @param plugin
	 *            The plug-in resource.
	 * @return The checked {@link PluginType}
	 */
	protected PluginType determinePluginType(final ServicePlugin plugin) {
		// Determine the type from the key by convention
		final PluginType result = PluginType.values()[StringUtils.countMatches(plugin.getKey(), ':')];

		// Double check the convention with related interface
		final PluginType interfaceType;
		if (plugin instanceof ToolPlugin) {
			interfaceType = PluginType.TOOL;
		} else {
			interfaceType = PluginType.SERVICE;
		}
		if (interfaceType != result) {
			throw new TechnicalException(String.format("Incompatible type from the key (%s -> %s) vs type from the interface (%s)", plugin.getKey(),
					result, interfaceType));
		}
		return result;
	}

	/**
	 * Insert the configuration entities of the plug-in. This function can be called multiple times : a check prevent
	 * duplicate entries.
	 *
	 * @param plugin
	 *            The related plug-in
	 * @param csvEntities
	 *            The managed entities where CSV data need to be persisted with this plug-in.
	 * @throws IOException
	 *             When the CSV management failed.
	 */
	protected void configurePluginEntities(final FeaturePlugin plugin, final List<Class<?>> csvEntities) throws IOException {
		//
		final ClassLoader classLoader = plugin.getClass().getClassLoader();

		// Compute the location of this plug-in, ensuring the
		final String pluginLocation = getPluginLocation(plugin).toString();
		for (final Class<?> entityClass : csvEntities) {
			// Build the required CSV file
			final String csv = "csv/"
					+ StringUtils.join(StringUtils.splitByCharacterTypeCamelCase(entityClass.getSimpleName()), '-').toLowerCase(Locale.ENGLISH)
					+ ".csv";
			configurePluginEntity(Collections.list(classLoader.getResources(csv)).stream(), entityClass, pluginLocation);
		}
	}

	/**
	 * Return the file system location corresponding to the given plug-in.
	 *
	 * @param plugin
	 *            The related plug-in
	 * @return The URL corresponding to the location.
	 */
	protected URL getPluginLocation(final FeaturePlugin plugin) {
		return plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
	}

	protected <T> void configurePluginEntity(final Stream<URL> csv, final Class<T> entityClass, final String pluginLocation) throws IOException {
		// Accept the CSV file only from the JAR/folder where the plug-in is installed from
		try (InputStreamReader input = new InputStreamReader(csv
				.filter(u -> u.getPath().startsWith(pluginLocation) || u.toString().startsWith(pluginLocation)).findFirst()
				.orElseThrow(() -> new TechnicalException(String.format("Unable to find CSV file for entity %s", entityClass.getSimpleName())))
				.openStream(), StandardCharsets.UTF_8)) {

			// Build and save the entities managed by this plug-in
			csvForJpa.toJpa(entityClass, input, true, false, e -> {
				persistAsNeeded(entityClass, e);
			});
			em.flush();
			em.clear();
		}

	}

	/**
	 * Persist the given entity only if it is not yet persisted. This is not an update mode.
	 *
	 * @param entityClass
	 *            The entity class to persist.
	 * @param entity
	 *            The entity read from the CSV, and to persist.
	 * @param <T>
	 *            The entity type.
	 */
	protected <T> void persistAsNeeded(final Class<T> entityClass, T entity) {
		if (entity instanceof AbstractBusinessEntity) {
			// Check for duplicate before the insert
			if (em.find(entityClass, ((AbstractBusinessEntity<?>) entity).getId()) == null) {
				em.persist(entity);
			}
		} else if (entity instanceof INamableBean) {
			if (em.createQuery("SELECT 1 FROM " + entityClass.getName() + " WHERE name = :name")
					.setParameter("name", ((INamableBean<?>) entity).getName()).getResultList().isEmpty()) {
				em.persist(entity);
			}
		} else {
			em.persist(entity);
		}
	}

	/**
	 * Build a new {@link Node} from the given plug-in instance using the naming convention to link the parent.
	 *
	 * @param service
	 *            The service plug-in to add as a node.
	 * @return The new {@link Node}
	 */
	protected Node newNode(final ServicePlugin service) {
		final Node node = new Node();
		node.setId(service.getKey());
		node.setName(service.getName());

		// Add default values
		node.setTag("functional");
		node.setTagUiClasses("fas fa-suitcase");
		node.setMode(SubscriptionMode.LINK);
		node.setUiClasses("$" + service.getName());

		// Link to the parent
		node.setRefined(getParentNode(service.getKey()));

		return node;
	}

	/**
	 * Return the parent node from a key. The node entity is retrieved from the data base without cache.
	 *
	 * @param key
	 *            The plug-in key.
	 * @return the parent node entity or <code>null</code> when this the top most definition. Note if there is an
	 *         expected parent by convention, and the parent is not found, an error will be raised.
	 */
	protected Node getParentNode(final String key) {
		final String parentKey = key.substring(0, key.lastIndexOf(':'));
		if (parentKey.indexOf(':') == -1) {
			// Was already the top most parent
			return null;
		}

		// The closest parent has been found by convention, must be available in database
		return nodeRepository.findOneExpected(parentKey);
	}

	/**
	 * Return a fail-safe computed version of the given {@link FeaturePlugin}
	 *
	 * @param plugin
	 *            The plug-in instance
	 * @return The version from the MANIFEST or the timestamp. <code>?</code> when an error occurs.
	 */
	protected String getVersion(final FeaturePlugin plugin) {
		return Optional.ofNullable(plugin.getVersion()).orElseGet(() -> {
			// Not explicit version
			try {
				return getLastModifiedTime(plugin);
			} catch (final IOException | URISyntaxException e) {
				log.warn("Unable to determine the version of plug-in {}", plugin.getClass(), e);

				// Fail-safe version
				return "?";
			}
		});
	}

}
