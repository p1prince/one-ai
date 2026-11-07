package com.oneai.reference.devicecontrol

import org.json.JSONArray
import org.json.JSONObject

/** Generic version-1 Bubble JSON; rendered by ONE AI without loading plugin-specific UI code. */
object DeviceControlBubbleView {
    const val SERVICE_ID = "device_control"

    fun render(): String {
        val elements = JSONArray()
        elements.put(
            JSONObject()
                .put("t", "label")
                .put("text", "Choose an Android settings screen. ONE AI asks before opening it; Android remains in control of the actual setting and any system confirmation.")
                .put("size", 14)
        )
        DeviceControlPolicy.actions().forEach { action ->
            elements.put(
                JSONObject()
                    .put("t", "button")
                    .put("label", action.label)
                    .put("command", action.id)
            )
        }
        return JSONObject().put("v", 1).put("elements", elements).toString()
    }
}
