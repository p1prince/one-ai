package com.oneai.reference.appcontrol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a version-1 declarative view understood by ONE AI's generic Bubble renderer.
 * Each installed app is a button with literal, reviewable arguments; no plugin-specific UI code
 * is loaded into the host. The 38-app bound keeps the view within the host's 40-element limit.
 */
object AppControlBubbleView {
    const val SERVICE_ID = "app_control"
    const val MAX_VISIBLE_APPS = 38

    fun render(apps: List<AppLaunchTarget>): String {
        val elements = JSONArray()
        elements.put(
            JSONObject()
                .put("t", "label")
                .put("text", "Choose an installed app to open. ONE AI will ask before launching it. This does not sign in, read private app data, or automate actions inside it.")
                .put("size", 14)
        )
        val visible = apps.take(MAX_VISIBLE_APPS)
        if (visible.isEmpty()) {
            elements.put(JSONObject().put("t", "label").put("text", "No launcher-visible apps were found."))
        } else {
            visible.forEach { app ->
                val label = app.label.trim().take(AppControlCommands.MAX_LABEL).ifBlank { app.packageName }
                elements.put(
                    JSONObject()
                        .put("t", "button")
                        .put("label", label.take(60))
                        .put("command", AppControlCommands.LAUNCH_COMMAND)
                        .put(
                            "args",
                            JSONObject()
                                .put("package_name", app.packageName)
                                .put("app_label", label)
                        )
                )
            }
        }
        return JSONObject().put("v", 1).put("elements", elements).toString()
    }
}
