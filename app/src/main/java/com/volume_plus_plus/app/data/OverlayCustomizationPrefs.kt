package com.volume_plus_plus.app.data

import android.content.Context
import com.volume_plus_plus.app.overlay.ComponentCustomization
import com.volume_plus_plus.app.overlay.OverlayVersion
import com.volume_plus_plus.app.overlay.PanelColors
import com.volume_plus_plus.app.overlay.PanelOffset
import com.volume_plus_plus.app.overlay.VersionCustomization
import com.volume_plus_plus.app.overlay.withDefaultColors
import com.volume_plus_plus.app.overlay.withDefaultOffsets
import org.json.JSONObject

/**
 * Stores each [OverlayVersion]'s [VersionCustomization] independently, keyed by the version's enum
 * name, so every style (Android 7–15) saves its own position/colour tweaks without touching any
 * other. Read by both the editor UI and the live overlay (so a saved change applies the next time the
 * panel shows). Serialised as compact JSON — only fields that differ from the defaults are written,
 * so an untouched version stores nothing.
 */
class OverlayCustomizationPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("overlay_customization", Context.MODE_PRIVATE)

    fun getFor(version: OverlayVersion): VersionCustomization {
        val raw = prefs.getString(version.name, null) ?: return VersionCustomization()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(VersionCustomization())
    }

    fun setFor(version: OverlayVersion, customization: VersionCustomization) {
        prefs.edit().putString(version.name, encode(customization).toString()).apply()
    }

    /** Drop [version]'s customization entirely, restoring the untouched skin defaults. */
    fun clear(version: OverlayVersion) {
        prefs.edit().remove(version.name).apply()
    }

    /**
     * Put [version]'s panels back at their default docked spots — every component, in both
     * orientations — keeping whatever colours it has.
     */
    fun restoreDefaultPosition(version: OverlayVersion) = update(version) { it.withDefaultOffsets() }

    /** Drop [version]'s colour overrides — every component — keeping where it has been positioned. */
    fun restoreDefaultColors(version: OverlayVersion) = update(version) { it.withDefaultColors() }

    /** Store [transform]ed, falling back to [clear] once nothing is customized, so a version restored
     *  all the way back to stock stores nothing again. */
    private fun update(
        version: OverlayVersion,
        transform: (VersionCustomization) -> VersionCustomization,
    ) {
        val updated = transform(getFor(version))
        if (updated == VersionCustomization()) clear(version) else setFor(version, updated)
    }

    // ── (de)serialisation ─────────────────────────────────────────────────────────────────────────

    private fun encode(c: VersionCustomization) = JSONObject().apply {
        put("main", encodeComponent(c.main))
        put("expanded", encodeComponent(c.expanded))
        put("output", encodeComponent(c.output))
    }

    private fun encodeComponent(c: ComponentCustomization) = JSONObject().apply {
        put("portrait", encodeOffset(c.portrait))
        put("landscape", encodeOffset(c.landscape))
        put("colors", encodeColors(c.colors))
    }

    private fun encodeOffset(o: PanelOffset) = JSONObject().apply {
        put("dx", o.dxDp.toDouble())
        put("dy", o.dyDp.toDouble())
    }

    private fun encodeColors(colors: PanelColors) = JSONObject().apply {
        colors.container?.let { put("container", it) }
        colors.fill?.let { put("fill", it) }
        colors.track?.let { put("track", it) }
        colors.icon?.let { put("icon", it) }
        colors.accent?.let { put("accent", it) }
        colors.text?.let { put("text", it) }
        colors.secondary?.let { put("secondary", it) }
        colors.mediaIcon?.let { put("mediaIcon", it) }
        colors.modeIcon?.let { put("modeIcon", it) }
        colors.overflow?.let { put("overflow", it) }
        colors.dot?.let { put("dot", it) }
        colors.outputSurface?.let { put("outputSurface", it) }
        colors.doneBg?.let { put("doneBg", it) }
        colors.doneText?.let { put("doneText", it) }
        colors.title?.let { put("title", it) }
    }

    private fun decode(json: JSONObject) = VersionCustomization(
        main = decodeComponent(json.optJSONObject("main")),
        expanded = decodeComponent(json.optJSONObject("expanded")),
        output = decodeComponent(json.optJSONObject("output")),
    )

    private fun decodeComponent(json: JSONObject?): ComponentCustomization {
        if (json == null) return ComponentCustomization()
        return ComponentCustomization(
            portrait = decodeOffset(json.optJSONObject("portrait")),
            landscape = decodeOffset(json.optJSONObject("landscape")),
            colors = decodeColors(json.optJSONObject("colors")),
        )
    }

    private fun decodeOffset(json: JSONObject?): PanelOffset {
        if (json == null) return PanelOffset()
        return PanelOffset(
            dxDp = json.optDouble("dx", 0.0).toFloat(),
            dyDp = json.optDouble("dy", 0.0).toFloat(),
        )
    }

    private fun decodeColors(json: JSONObject?): PanelColors {
        if (json == null) return PanelColors()
        fun opt(key: String): Int? = if (json.has(key)) json.getInt(key) else null
        return PanelColors(
            container = opt("container"),
            fill = opt("fill"),
            track = opt("track"),
            icon = opt("icon"),
            accent = opt("accent"),
            text = opt("text"),
            secondary = opt("secondary"),
            mediaIcon = opt("mediaIcon"),
            modeIcon = opt("modeIcon"),
            overflow = opt("overflow"),
            dot = opt("dot"),
            outputSurface = opt("outputSurface"),
            doneBg = opt("doneBg"),
            doneText = opt("doneText"),
            title = opt("title"),
        )
    }
}
