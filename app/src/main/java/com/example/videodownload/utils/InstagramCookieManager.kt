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

    /**
     * Verifica contra el servidor de Instagram que una sesión (cookie header)
     * realmente funciona. Evita guardar sesiones viejas/expiradas que el WebView
     * conserva de intentos anteriores y que hacen que el login "no pegue".
     */
    fun validateSession(rawCookieString: String): Boolean {
        if (rawCookieString.isBlank() ||
            !rawCookieString.contains("sessionid=")
        ) return false

        return try {
            val url = java.net.URL(
                "https://www.instagram.com/api/v1/users/web_profile_info/?username=instagram"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Cookie", rawCookieString)
            // app id web público de Instagram; requerido por esta API interna
            conn.setRequestProperty("x-ig-app-id", "936619743392459")
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest")

            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "[validateSession] Código HTTP de validación: $code")
            // 200 = sesión válida; 401/403/302 = sesión expirada o inválida
            // (o endpoint bloqueado; se maneja en el llamador)
            code == 200
        } catch (e: Exception) {
            Log.w(TAG, "[validateSession] Error validando sesión: ${e.message}")
            false
        }
    }

    /**
     * Elimina solo las cookies de Instagram del WebView (sin tocar las de
     * YouTube/Facebook), para forzar un login limpio cuando la sesión expiró.
     */
    fun clearInstagramWebViewCookies(rawCookieString: String) {
        try {
            val cm = CookieManager.getInstance()
            rawCookieString.split(";").forEach {
                val name = it.trim().substringBefore("=")
                if (name.isNotBlank()) {
                    cm.setCookie(
                        "https://www.instagram.com",
                        "$name=; Domain=.instagram.com; Path=/; Max-Age=0"
                    )
                }
            }
            cm.flush()
            Log.i(TAG, "[clearInstagramWebViewCookies] Cookies de Instagram limpiadas del WebView.")
        } catch (e: Exception) {
            Log.e(TAG, "[clearInstagramWebViewCookies] Error: ${e.message}")
        }
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
            // Forzar escritura de las cookies del WebView a disco antes de leer.
            CookieManager.getInstance().flush()

            val cookieString = CookieManager.getInstance().getCookie("https://www.instagram.com")
            if (cookieString.isNullOrBlank() || !cookieString.contains("sessionid")) {
                Log.w(TAG, "[saveCookiesFromWebView] No se encontró cookie 'sessionid' en la sesión de Instagram.")
                return false
            }

            // Instagram coloca un 'sessionid' vacío/placeholer incluso sin iniciar
            // sesión. Una sesión REAL también tiene 'ds_user_id' y un sessionid
            // con contenido. Sin esto, el diálogo se cierra a mitad del login.
            val hasUserId = cookieString.contains("ds_user_id=")
            val hasRealSession = cookieString.split(";").any {
                val p = it.trim()
                p.startsWith("sessionid=") && p.length > "sessionid=".length + 10
            }
            if (!hasUserId || !hasRealSession) {
                Log.w(TAG, "[saveCookiesFromWebView] Cookie 'sessionid' presente pero sin sesión válida (ds_user_id=$hasUserId, sessionidReal=$hasRealSession).")
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
