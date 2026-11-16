package com.example.videodownload.utils

import android.content.Context

/**
 * Ajustes de la app guardados en SharedPreferences.
 * - Modo de tema: system / dark / light
 * - Subcarpeta de descargas (dentro de Movies/, Music/ o Pictures/ según tipo)
 */
object SettingsManager {

    private const val PREFS_NAME = "jpdownload_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DOWNLOAD_SUBFOLDER = "download_subfolder"
    private const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"

    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val DEFAULT_SUBFOLDER = "VideoDownload"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------- TEMA -------------------------

    fun getThemeMode(context: Context): String {
        return prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    // ------------------- CARPETA DESCARGAS -------------------

    fun getDownloadSubfolder(context: Context): String {
        return prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER)
            ?: DEFAULT_SUBFOLDER
    }

    /**
     * Guarda la subcarpeta de descargas. Sanitiza el nombre para que sea
     * un nombre de carpeta válido en Android (sin / \ : * ? " < | ni puntos
     * de tramo vacío).
     */
    fun setDownloadSubfolder(context: Context, name: String): Boolean {
        val sanitized = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (sanitized.isEmpty()) return false

        prefs(context).edit().putString(KEY_DOWNLOAD_SUBFOLDER, sanitized).apply()
        return true
    }

    // ------------------- CARPETA PERSONALIZADA (SAF) -------------------

    /** Uri del árbol de carpetas elegido por el usuario, o null si usa la predeterminada. */
    fun getDownloadTreeUri(context: Context): android.net.Uri? {
        val stored = prefs(context).getString(KEY_DOWNLOAD_TREE_URI, null)
        return if (stored.isNullOrBlank()) null else android.net.Uri.parse(stored)
    }

    /** Guarda (o borra con null) la carpeta personalizada de descargas. */
    fun setDownloadTreeUri(context: Context, uri: android.net.Uri?) {
        prefs(context).edit()
            .putString(KEY_DOWNLOAD_TREE_URI, uri?.toString())
            .apply()
    }
}
