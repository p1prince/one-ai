package com.oneai.app.plugins

/**
 * Stage 8 — manifest v2 for independently installed, signed plugin APKs (decision D1).
 *
 * Everything in this file is pure Kotlin with no Android imports, so it is unit-testable on the
 * JVM. The Android-specific reading of the XML resource lives in a later commit and only builds
 * an [XmlNode] tree that [ExternalManifestParser] maps and validates.
 *
 * A manifest is declarative: it is read from the plugin package *without running plugin code*.
 * The plugin ID is the Android package name; the version is the APK's versionName/versionCode.
 */
object PluginApi {
    /** Plugin API level implemented by this build of ONE AI. Raise it only for a documented contract change. */
    /**
     * 2 adds [onLaunch] (a plugin may ask ONE AI to open a screen after the user agrees). Plugins using it declare minCoreApi="2".
     * 3 adds `applyConfig` (host-delivered per-plugin settings, decision D5). Plugins declaring `<settings>` declare minCoreApi="3".
     */
    const val LEVEL = 3

    /** Oldest `targetCoreApi` the host still treats as fully supported. */
    const val MIN_SUPPORTED_TARGET = 1

    /** Intent action a plugin's exported service declares so the host can discover it. */
    const val SERVICE_ACTION = "com.oneai.plugin.action.PLUGIN"

    /** `<meta-data>` name on the service pointing at the manifest XML resource. */
    const val MANIFEST_META_DATA = "com.oneai.plugin.manifest"

    const val MANIFEST_VERSION = 2

    /** Lowest `minCoreApi` a plugin declaring `<settings>` may use: level 3 adds host-delivered configuration (D5). */
    const val SETTINGS_MIN_API = 3
}

data class ExternalCommandDeclaration(
    val name: String,
    val title: String,
    val description: String,
    val capability: String
)

/** Optional Bubble contribution. A plugin without one can never appear in the Bubble. */
data class ExternalBubbleDeclaration(val service: String, val title: String)

data class ExternalPluginManifest(
    val manifestVersion: Int = PluginApi.MANIFEST_VERSION,
    /** Android package name; doubles as the plugin ID. */
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val name: String,
    val description: String = "",
    val minCoreApi: Int,
    val targetCoreApi: Int,
    val capabilities: List<String>,
    val commands: List<ExternalCommandDeclaration> = emptyList(),
    val bubble: ExternalBubbleDeclaration? = null,
    val dependencies: List<String> = emptyList(),
    /** Optional generic configuration schema (Stage 9.6a). Null means the plugin has no settings. */
    val settings: PluginSettingsSchema? = null
) {
    val id: String get() = packageName

    /**
     * Bridge to the manifest type used by the existing runtime, gateway and plugin records, so those
     * keep working unchanged. The record shape (not the transport) is what manifestVersion 1 means there.
     */
    fun toRecordManifest() = PluginManifest(
        manifestVersion = PluginManifest.CURRENT_VERSION,
        id = packageName, name = name, version = versionName, description = description,
        capabilities = capabilities, dependencies = dependencies
    )
}

object ExternalManifestValidator {
    const val MAX_COMMANDS = 50
    const val MAX_CAPABILITIES = 12
    const val MAX_DEPENDENCIES = 16
    const val MAX_NAME = 60
    const val MAX_DESCRIPTION = 500
    const val MAX_COMMAND_TITLE = 80
    const val MAX_COMMAND_DESCRIPTION = 300
    private const val MAX_ID_LENGTH = 100

    private val pluginId = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
    private val identifier = Regex("[a-z][a-z0-9_]{0,39}")
    private val semanticVersion = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?")

    /** Returns a normalised copy, or a failure whose message is safe to show the user. */
    fun validate(manifest: ExternalPluginManifest): Result<ExternalPluginManifest> = runCatching {
        require(manifest.manifestVersion == PluginApi.MANIFEST_VERSION) {
            "Unsupported plugin manifest version: ${manifest.manifestVersion}"
        }
        val id = manifest.packageName.trim()
        require(id.length <= MAX_ID_LENGTH && pluginId.matches(id)) {
            "Plugin package names must be lowercase reverse-domain names, e.g. dev.example.notes"
        }
        val version = manifest.versionName.trim()
        require(semanticVersion.matches(version)) { "Plugin versionName must use semantic versioning (for example 1.2.0)" }
        require(manifest.versionCode > 0) { "Plugin versionCode must be positive" }
        val name = manifest.name.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME) { "Plugin name must be 1–$MAX_NAME characters" }
        val description = manifest.description.trim()
        require(description.length <= MAX_DESCRIPTION) { "Plugin description must be $MAX_DESCRIPTION characters or fewer" }
        require(manifest.minCoreApi >= 1) { "minCoreApi must be 1 or higher" }
        require(manifest.targetCoreApi >= manifest.minCoreApi) { "targetCoreApi must not be lower than minCoreApi" }

        require(manifest.capabilities.size <= MAX_CAPABILITIES) { "A plugin may declare at most $MAX_CAPABILITIES capabilities" }
        val capabilities = manifest.capabilities.map { it.trim().lowercase() }.distinct()
        require(capabilities.isNotEmpty()) { "Declare at least one capability" }
        require(capabilities.all { it in PluginManifestValidator.supportedCapabilities }) {
            "One or more capabilities are not supported"
        }

        require(manifest.commands.size <= MAX_COMMANDS) { "A plugin may declare at most $MAX_COMMANDS commands" }
        val commands = manifest.commands.map { command ->
            val commandName = command.name.trim()
            require(identifier.matches(commandName)) {
                "Command names use lowercase letters, digits and underscores (max 40)"
            }
            val title = command.title.trim()
            require(title.isNotEmpty() && title.length <= MAX_COMMAND_TITLE) {
                "Command titles must be 1–$MAX_COMMAND_TITLE characters"
            }
            val commandDescription = command.description.trim()
            require(commandDescription.length <= MAX_COMMAND_DESCRIPTION) {
                "Command descriptions must be $MAX_COMMAND_DESCRIPTION characters or fewer"
            }
            val capability = command.capability.trim().lowercase()
            require(capability in capabilities) {
                "Command '$commandName' uses a capability the plugin does not declare"
            }
            ExternalCommandDeclaration(commandName, title, commandDescription, capability)
        }
        require(commands.map { it.name }.distinct().size == commands.size) { "Duplicate command names are not allowed" }

        val bubble = manifest.bubble?.let { declared ->
            val service = declared.service.trim()
            require(identifier.matches(service)) { "Bubble service IDs use lowercase letters, digits and underscores (max 40)" }
            val title = declared.title.trim()
            require(title.isNotEmpty() && title.length <= MAX_NAME) { "Bubble titles must be 1–$MAX_NAME characters" }
            ExternalBubbleDeclaration(service, title)
        }

        require(manifest.dependencies.size <= MAX_DEPENDENCIES) { "A plugin may declare at most $MAX_DEPENDENCIES dependencies" }
        val dependencies = manifest.dependencies.map { it.trim() }
        require(dependencies.distinct().size == dependencies.size) { "Duplicate plugin dependencies are not allowed" }
        require(dependencies.all { it.length <= MAX_ID_LENGTH && pluginId.matches(it) }) {
            "Every dependency must be a valid reverse-domain plugin ID"
        }
        require(id !in dependencies) { "A plugin cannot depend on itself" }

        val settings = manifest.settings?.let { declared ->
            require(manifest.minCoreApi >= PluginApi.SETTINGS_MIN_API) {
                "A plugin that declares settings must declare minCoreApi ${PluginApi.SETTINGS_MIN_API} or higher"
            }
            PluginSettingsValidator.validateSchema(declared).getOrThrow()
        }

        manifest.copy(
            packageName = id, versionName = version, name = name, description = description,
            capabilities = capabilities, commands = commands, bubble = bubble, dependencies = dependencies,
            settings = settings
        )
    }
}

sealed interface Compatibility {
    data object Compatible : Compatibility
    /** Usable, but the plugin targets an API level the host is phasing out. */
    data class CompatibleWithWarning(val warning: String) : Compatibility
    data class Incompatible(val reason: String) : Compatibility

    val usable: Boolean get() = this !is Incompatible
}

object PluginCompatibility {
    fun check(
        manifest: ExternalPluginManifest,
        hostLevel: Int = PluginApi.LEVEL,
        minSupportedTarget: Int = PluginApi.MIN_SUPPORTED_TARGET
    ): Compatibility = when {
        manifest.minCoreApi > hostLevel ->
            Compatibility.Incompatible("Needs ONE AI plugin API ${manifest.minCoreApi}; this ONE AI provides $hostLevel. Update ONE AI.")
        manifest.targetCoreApi < minSupportedTarget ->
            Compatibility.CompatibleWithWarning("Built for an older plugin API (${manifest.targetCoreApi}); ask the developer for an update.")
        else -> Compatibility.Compatible
    }

    /** Dependencies that are not installed *and* enabled, in declaration order. */
    fun missingDependencies(manifest: ExternalPluginManifest, enabledPluginIds: Set<String>): List<String> =
        manifest.dependencies.filterNot { it in enabledPluginIds }
}

/**
 * Decides whether an updated plugin may keep running or must wait for the user to approve the new
 * declaration (design §3: an update that adds powers must never take effect silently).
 */
object PluginUpdateReview {
    data class Result(val consentRequired: Boolean, val reasons: List<String>)

    fun review(previous: ExternalPluginManifest, updated: ExternalPluginManifest): Result {
        val reasons = buildList {
            val newCapabilities = updated.capabilities - previous.capabilities.toSet()
            if (newCapabilities.isNotEmpty()) add("New capabilities: ${newCapabilities.joinToString()}")
            val previousCommands = previous.commands.associateBy { it.name }
            val newCommands = updated.commands.filter { it.name !in previousCommands }
            if (newCommands.isNotEmpty()) add("New commands: ${newCommands.joinToString { it.name }}")
            val widened = updated.commands.filter { previousCommands[it.name]?.let { old -> old.capability != it.capability } == true }
            if (widened.isNotEmpty()) add("Commands now use a different capability: ${widened.joinToString { it.name }}")
            if (updated.bubble != null && updated.bubble != previous.bubble) add("Bubble access declaration added or changed")
            val newDependencies = updated.dependencies - previous.dependencies.toSet()
            if (newDependencies.isNotEmpty()) add("New dependencies: ${newDependencies.joinToString()}")
        }
        return Result(reasons.isNotEmpty(), reasons)
    }
}

/** Minimal, Android-free element tree. The Android layer builds it from the plugin's XML resource. */
data class XmlNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<XmlNode> = emptyList()
)

object ExternalManifestParser {
    private const val MAX_ELEMENTS = 200
    private const val MAX_ATTRIBUTE_LENGTH = 1_000

    /**
     * Maps `<one-ai-plugin>` to a validated manifest. Identity comes from the installed package
     * (never from the XML). Unknown elements and attributes are ignored so a plugin built for a newer
     * API can still be read; anything the host acts on is validated strictly.
     */
    fun parse(root: XmlNode, packageName: String, versionName: String, versionCode: Long): Result<ExternalPluginManifest> = runCatching {
        require(root.name == "one-ai-plugin") { "Manifest root must be <one-ai-plugin>" }
        require(1 + root.children.size + root.children.sumOf { it.children.size } <= MAX_ELEMENTS) { "Manifest is too large" }
        require((listOf(root) + root.children).all { node -> node.attributes.values.all { it.length <= MAX_ATTRIBUTE_LENGTH } }) {
            "Manifest attribute values are too long"
        }
        val manifestVersion = root.int("manifestVersion") ?: error("manifestVersion is required")
        val info = root.children.singleOrNull { it.name == "info" } ?: error("Exactly one <info> element is required")
        val commands = root.children.filter { it.name == "command" }.map {
            ExternalCommandDeclaration(
                name = it.attributes["name"].orEmpty(), title = it.attributes["title"].orEmpty(),
                description = it.attributes["description"].orEmpty(), capability = it.attributes["capability"].orEmpty()
            )
        }
        val bubbles = root.children.filter { it.name == "bubble" }
        require(bubbles.size <= 1) { "At most one <bubble> element is allowed" }
        val settingsNodes = root.children.filter { it.name == "settings" }
        require(settingsNodes.size <= 1) { "At most one <settings> element is allowed" }
        val settings = settingsNodes.firstOrNull()?.let { PluginSettingsParser.parse(it).getOrThrow() }
        ExternalPluginManifest(
            manifestVersion = manifestVersion,
            packageName = packageName, versionName = versionName, versionCode = versionCode,
            name = info.attributes["name"].orEmpty(), description = info.attributes["description"].orEmpty(),
            minCoreApi = root.int("minCoreApi") ?: error("minCoreApi is required"),
            targetCoreApi = root.int("targetCoreApi") ?: error("targetCoreApi is required"),
            capabilities = root.children.filter { it.name == "capability" }.map { it.attributes["name"].orEmpty() },
            commands = commands,
            bubble = bubbles.firstOrNull()?.let { ExternalBubbleDeclaration(it.attributes["service"].orEmpty(), it.attributes["title"].orEmpty()) },
            dependencies = root.children.filter { it.name == "depends" }.map { it.attributes["plugin"].orEmpty() },
            settings = settings
        )
    }.mapCatching { ExternalManifestValidator.validate(it).getOrThrow() }

    private fun XmlNode.int(attribute: String): Int? = attributes[attribute]?.trim()?.toIntOrNull()
}
