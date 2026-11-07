package com.oneai.app.core

/**
 * Persistence-only storage for the user's plugin catalogue sources (Stage 9.7c). It holds one opaque JSON
 * string; the plugins package owns its format, validation and meaning, so the core store depends on nothing
 * plugin-specific.
 */
interface CatalogueSourceStorage {
    /** The stored JSON, or null when nothing has been stored. */
    fun readCatalogueSources(): String?
    fun writeCatalogueSources(json: String)
}
