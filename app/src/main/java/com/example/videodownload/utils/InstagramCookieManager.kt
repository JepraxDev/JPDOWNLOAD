package com.example.videodownload.utils

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.io.FileWriter

object InstagramCookieManager {

    private const val TAG = "InstagramCookieManager"
    private const val COOKIE_FILE_NAME = "instagram_cookies.txt"

    fun getCookieFile(context: Context): File {
        return File(context.filesDir, COOKIE_FILE_NAME)
    }

    fun hasCookies(context: Context): Boolean {
        val file = getCookieFile(context)
        return file.exists() && file.length() > 50
    }

    // Devuelve las cookies guardadas como header "k=v; k2=v2" o null si no hay
    fun getCookieHeader(context: Context): String? {
        val file = getCookieFile(context)
        if (!file.exists()) return null
        return try {
            val pairs = file.readLines()
                .filter { !it.startsWith("#") && it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 7) "${parts[5]}=${parts[6]}" else null
                }
            if (pairs.isNotEmpty()) pairs.joinToString("; ") else null
        } catch (e: Exception) {
            Log.w(TAG, "[getCookieHeader] Error leyendo cookies: ${e.message}")
            null
        }
    }

    fun saveCookiesFromWebView(context: Context): Boolean {
        try {
            val cookieString = CookieManager.getInstance().getCookie("https://www.instagram.com")
            if (cookieString.isNullOrBlank() || !cookieString.contains("sessionid")) {
                Log.w(TAG, "[saveCookiesFromWebView] No se encontró cookie 'sessionid' en la sesión de Instagram.")
                return false
            }

            val cookieFile = getCookieFile(context)
            FileWriter(cookieFile, false).use { writer ->
                writer.write("# Netscape HTTP Cookie File\n")
                writer.write("# https://curl.haxx.se/rfc/cookie_spec.html\n")
                writer.write("# This is a generated file! Do not edit.\n\n")

                val pairs = cookieString.split(";")
                for (pair in pairs) {
                    val trimmed = pair.trim()
                    val eqIndex = trimmed.indexOf("=")
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        writer.write(".instagram.com\tTRUE\t/\tTRUE\t2147483647\t$key\t$value\n")
                    }
                }
            }

            Log.i(TAG, "[saveCookiesFromWebView] Cookies de Instagram guardadas en almacenamiento privado: ${cookieFile.absolutePath} (${cookieFile.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "[saveCookiesFromWebView] Error guardando cookies de Instagram: ${e.message}", e)
            return false
        }
    }

    fun clearCookies(context: Context) {
        try {
            val cookieFile = getCookieFile(context)
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
            CookieManager.getInstance().removeAllCookies(null)
            Log.i(TAG, "[clearCookies] Cookies de Instagram eliminadas del dispositivo.")
        } catch (e: Exception) {
            Log.e(TAG, "[clearCookies] Error eliminando cookies: ${e.message}")
        }
    }
}
