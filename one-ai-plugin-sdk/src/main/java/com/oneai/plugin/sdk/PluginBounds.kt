package com.oneai.plugin.sdk

/** Hard limits on everything that crosses the plugin boundary. Oversized data fails the call; it is never truncated silently. */
object PluginBounds {
    const val SDK_API = 3

    /** First wire level that has `applyConfig`. The host never calls it on a plugin that reports less. */
    const val CONFIG_MIN_API = 3
    const val MAX_CONFIG_ENTRIES = 24
    const val MAX_CONFIG_VALUE = 500
    const val MAX_CONFIG_SNAPSHOT_BYTES = 8 * 1024
    const val MAX_ARGS = 32
    const val MAX_ARG_KEY = 80
    const val MAX_ARG_VALUE = 4_000
    const val MAX_TITLE = 120
    const val MAX_CONTENT = 20_000
    const val MAX_ERROR_MESSAGE = 300
    const val MAX_ACTIVITY = 300
    const val MAX_LAUNCH_LABEL = 100
    const val MAX_VIEW_JSON = 20_000
    const val DEFAULT_TIMEOUT_MS = 30_000L

    private val requestId = Regex("[A-Za-z0-9_-]{1,64}")
    private val commandName = Regex("[a-z][a-z0-9_]{0,39}")
    private val errorCode = Regex("[A-Z][A-Z0-9_]{0,39}")

    fun isValidRequestId(value: String?) = value != null && requestId.matches(value)
    fun isValidCommandName(value: String?) = value != null && commandName.matches(value)

    /** Plugin-supplied codes must look like codes; anything else becomes PLUGIN_ERROR. */
    fun safeErrorCode(value: String?) = if (value != null && errorCode.matches(value)) value else "PLUGIN_ERROR"

    fun bound(value: String?, max: Int): String = (value ?: "").take(max)

    fun parseArgs(keys: List<String>?, values: List<String>?): Result<Map<String, String>> = runCatching {
        require(keys != null && values != null && keys.size == values.size) { "Arguments are malformed" }
        require(keys.size <= MAX_ARGS) { "Too many arguments" }
        require(keys.distinct().size == keys.size) { "Duplicate argument names" }
        keys.indices.associate { i ->
            require(keys[i].isNotEmpty() && keys[i].length <= MAX_ARG_KEY) { "Argument name is too long" }
            require(values[i].length <= MAX_ARG_VALUE) { "Argument value is too long" }
            keys[i] to values[i]
        }
    }

    private val configKey = Regex("[a-z][a-z0-9_]{0,39}")

    /**
     * Defence in depth for `applyConfig`: the host already validated the snapshot, but a plugin must not
     * trust that. Same limits as the host (24 entries, key syntax, 500-character values, 8 KB total).
     * A snapshot that breaks any limit is rejected whole, never truncated.
     */
    fun parseConfig(schemaVersion: Int, keys: List<String>?, values: List<String>?): Result<PluginConfig> = runCatching {
        require(schemaVersion >= 1) { "Configuration version is invalid" }
        require(keys != null && values != null && keys.size == values.size) { "Configuration is malformed" }
        require(keys.size <= MAX_CONFIG_ENTRIES) { "Too many settings" }
        require(keys.distinct().size == keys.size) { "Duplicate setting names" }
        var bytes = 0
        val map = LinkedHashMap<String, String>()
        keys.indices.forEach { i ->
            require(configKey.matches(keys[i])) { "Setting name is invalid" }
            require(values[i].length <= MAX_CONFIG_VALUE) { "Setting value is too long" }
            bytes += keys[i].toByteArray(Charsets.UTF_8).size + values[i].toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_CONFIG_SNAPSHOT_BYTES) { "Configuration is too large" }
            map[keys[i]] = values[i]
        }
        PluginConfig(schemaVersion, map)
    }

    fun outputProblem(title: String, content: String, launchLabel: String? = null): String? = when {
        title.length > MAX_TITLE -> "Result title is too long"
        content.length > MAX_CONTENT -> "Result is too large"
        launchLabel != null && (launchLabel.isBlank() || launchLabel.length > MAX_LAUNCH_LABEL) -> "Screen label must be 1-$MAX_LAUNCH_LABEL characters"
        else -> null
    }
}
