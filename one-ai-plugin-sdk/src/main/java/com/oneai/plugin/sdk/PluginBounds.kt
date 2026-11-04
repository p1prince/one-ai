package com.oneai.plugin.sdk

/** Hard limits on everything that crosses the plugin boundary. Oversized data fails the call; it is never truncated silently. */
object PluginBounds {
    const val SDK_API = 1
    const val MAX_ARGS = 32
    const val MAX_ARG_KEY = 80
    const val MAX_ARG_VALUE = 4_000
    const val MAX_TITLE = 120
    const val MAX_CONTENT = 20_000
    const val MAX_ERROR_MESSAGE = 300
    const val MAX_ACTIVITY = 300
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

    fun outputProblem(title: String, content: String): String? = when {
        title.length > MAX_TITLE -> "Result title is too long"
        content.length > MAX_CONTENT -> "Result is too large"
        else -> null
    }
}
