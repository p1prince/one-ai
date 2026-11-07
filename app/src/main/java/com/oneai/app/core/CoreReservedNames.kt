package com.oneai.app.core

/**
 * Identifiers the host keeps for itself so that an externally imported plugin can never take them over
 * (Stage 9.7b, decision D6 / D-H).
 *
 * These are **retained legacy identifiers**, not a list of built-in capabilities: earlier installs of ONE AI
 * stored plugin records and Bubble selections under these names, and an external plugin that reused one could
 * inherit that state. They are held here, independent of the old built-in catalogue, so the production
 * composition does not depend on that catalogue as a source of truth. Nothing about them implies a capability
 * ships with, or is planned for, the Core.
 */
object CoreReservedNames {
    /** Plugin IDs used by earlier installs. An external plugin may not register under any of them. */
    val pluginIds: Set<String> = setOf(
        "one.command.tools",
        "one.web.research",
        "one.screen.intelligence",
        "one.voice.interface",
        "one.automation.engine"
    )

    /** Bubble service names used by earlier installs. An external plugin may not claim any of them. */
    val bubbleServices: Set<String> = setOf(
        "web_research",
        "screen_intelligence",
        "voice_interface",
        "automation"
    )
}
