package com.oneai.app.core

/** Pure wording helpers for Settings, kept Android-free for deterministic tests. */
object SettingsText {
    /** "1 enabled plugin" / "2 enabled plugins"; the number decides singular or plural. */
    fun count(n: Int, singular: String): String = "$n ${if (n == 1) singular else singular + "s"}"

    fun enabledSummary(plugins: Int, commands: Int): String =
        "${count(plugins, "enabled plugin")} \u2022 ${count(commands, "enabled command")}"
}
