package com.example.videodownload.downloader

import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.data.repository.VideoExtractorRepository
import com.example.videodownload.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class AndroidVideoDownloader(private val context: Context) {

    companion object {
        private const val TAG = "VideoDownloader"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"
        private const val CHUNK_SIZE = 5 * 1024 * 1024L // 5 MB por fragmento
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun getPlatformHeaders(platform: Platform): Pair<String, String> {
        return when (platform) {
            Platform.TWITTER -> "https://x.com/" to "https://x.com"
            Platform.INSTAGRAM -> "https://www.instagram.com/" to "https://www.instagram.com"
            Platform.TIKTOK -> "https://www.tiktok.com/" to "https://www.tiktok.com"
            Platform.FACEBOOK -> "https://www.facebook.com/" to "https://www.facebook.com"
            Platform.YOUTUBE -> "https://www.youtube.com/" to "https://www.youtube.com"
            else -> "https://x.com/" to "https://x.com"
        }
    }

    fun validateDownloadedVideo(file: File): Boolean {
        if (!file.exists() || file.length() < 50_000) {
            Log.w(TAG, "[validateDownloadedVideo] Archivo no existe o es demasiado pequeño: ${file.length()} bytes")
            return false
        }

        try {
            val header = ByteArray(16)
            FileInputStream(file).use { input ->
                val read = input.read(header)
                if (read < 8) return false
            }

            val headerString = String(header, Charsets.US_ASCII).lowercase()

            // Descartar respuestas de error HTML o JSON
            if (headerString.startsWith("<!doc") || headerString.startsWith("<html") || headerString.startsWith("{")) {
                Log.e(TAG, "[validateDownloadedVideo] RECHAZADO: El archivo descargado es una página HTML/JSON y no un video.")
                return false
            }

            // Descartar imágenes JPEG (Magic bytes: 0xFF, 0xD8, 0xFF)
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                Log.e(TAG, "[validateDownloadedVideo] RECHAZADO: El archivo descargado es una imagen JPEG y no un video.")
                return false
            }

            // Descartar imágenes PNG (Magic bytes: 0x89, 0x50, 0x4E, 0x47)
            if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                Log.e(TAG, "[validateDownloadedVideo] RECHAZADO: El archivo descargado es una imagen PNG.")
                return false
            }

            // Validar MP4 / M4A (ftyp / isom / mp42)
            val isFtyp = header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()

            // Validar WebM (0x1A, 0x45, 0xDF, 0xA3)
            val isWebm = header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()

            if (isFtyp || isWebm || file.length() > 500_000) {
                Log.i(TAG, "[validateDownloadedVideo] VÁLIDO: Archivo multimedia verificado (${file.length() / 1024} KB)")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[validateDownloadedVideo] Error validando firma del archivo: ${e.message}")
        }
        return false
    }

    suspend fun downloadMediaAsync(
        videoInfo: VideoInfo,
        selectedOption: DownloadOption? = null,
        onProgress: ((progress: Int, statusMsg: String) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val option = selectedOption ?: DownloadOption(
            label = "HD MP4",
            downloadUrl = videoInfo.downloadUrl,
            isAudio = false,
            quality = videoInfo.quality,
            extension = "mp4"
        )

        // Si es una descarga DASH que requiere unir video + audio separados
        if (!option.isAudio && !option.audioDownloadUrl.isNullOrBlank()) {
            Log.d(TAG, "==================================================")
            Log.d(TAG, "[downloadMediaAsync] Descarga DASH Fragmentada (Chunked): Calidad=${option.quality} | Label=${option.label}")
            val cleanTitle = videoInfo.title.replace("[^a-zA-Z0-9_ -]".toRegex(), "_")
                .take(40)
                .ifEmpty { "media_${System.currentTimeMillis()}" }

            val cacheDir = context.cacheDir
            val tempVideoFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            val tempAudioFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")

            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                SettingsManager.getDownloadSubfolder(context)
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()

            val finalFile = File(moviesDir, "${videoInfo.platform.name.lowercase()}_${cleanTitle}_${System.currentTimeMillis()}.mp4")

            try {
                onProgress?.invoke(5, "Iniciando descarga de video HD...")
                Log.d(TAG, "[downloadMediaAsync] Obteniendo stream de video por trozos...")
                downloadFileChunked(
                    videoInfo = videoInfo,
                    rawUrl = option.downloadUrl,
                    targetFile = tempVideoFile,
                    label = "${option.label} (Video)",
                    baseProgress = 5,
                    maxProgressRange = 45,
                    onProgress = onProgress
                )

                onProgress?.invoke(50, "Iniciando descarga de pista de audio...")
                Log.d(TAG, "[downloadMediaAsync] Obteniendo stream de audio por trozos...")
                downloadFileChunked(
                    videoInfo = videoInfo,
                    rawUrl = option.audioDownloadUrl,
                    targetFile = tempAudioFile,
                    label = "${option.label} (Audio)",
                    baseProgress = 50,
                    maxProgressRange = 35,
                    onProgress = onProgress
                )

                onProgress?.invoke(88, "Unificando video y audio HD con MediaMuxer...")
                Log.d(TAG, "[downloadMediaAsync] Invocando AudioVideoMuxer.mux()...")
                val success = AudioVideoMuxer.mux(tempVideoFile, tempAudioFile, finalFile, option.label)

                // Limpieza de fragmentos temporales
                tempVideoFile.delete()
                tempAudioFile.delete()

                if (success && validateDownloadedVideo(finalFile)) {
                    onProgress?.invoke(100, "¡Guardando en Galería de Android!")
                    Log.i(TAG, "[downloadMediaAsync] DESCARGA Y MUXING EXITOSO: Archivo final ${finalFile.name} (${finalFile.length() / 1024} KB)")

                    // Registrar e indexar inmediatamente en la Galería de Android (MediaStore)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(finalFile.absolutePath),
                        arrayOf("video/mp4")
                    ) { path, uri ->
                        Log.i(TAG, "[MediaScanner] Archivo registrado exitosamente en la Galería: $path -> $uri")
                    }

                    return@withContext System.currentTimeMillis()
                } else {
                    if (finalFile.exists()) finalFile.delete()
                    Log.e(TAG, "[downloadMediaAsync] ERROR CRÍTICO: Archivo final no superó la validación multimedia.")
                    throw Exception("El video descargado es inválido o no contiene datos de video.")
                }
            } catch (e: Exception) {
                tempVideoFile.delete()
                tempAudioFile.delete()
                if (finalFile.exists()) finalFile.delete()
                Log.e(TAG, "[downloadMediaAsync] Excepción durante descarga DASH: ${e.message}", e)
                throw Exception("Error descargando video en HD: ${e.localizedMessage}")
            }
        }

        return@withContext downloadMedia(videoInfo, option)
    }

    private suspend fun downloadFileChunked(
        videoInfo: VideoInfo,
        rawUrl: String,
        targetFile: File,
        label: String,
        baseProgress: Int,
        maxProgressRange: Int,
        onProgress: ((progress: Int, statusMsg: String) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        var currentUrl = rawUrl
        var totalBytes = -1L

        val (referer, origin) = getPlatformHeaders(videoInfo.platform)

        // Petición HEAD/GET inicial para obtener Content-Length
        var attempts = 0
        while (attempts < 3) {
            attempts++
            try {
                val headRequest = Request.Builder()
                    .url(currentUrl)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                    .addHeader("Referer", referer)
                    .addHeader("Origin", origin)
                    .head()
                    .build()

                httpClient.newCall(headRequest).execute().use { response ->
                    if (response.code == 403 || response.code == 410) {
                        Log.w(TAG, "[ChunkedDownload] HTTP ${response.code} en HEAD. Refrescando URLs del video...")
                        val refreshedInfo = refreshVideoUrls(videoInfo.originalUrl)
                        if (refreshedInfo != null) {
                            val matchingOpt = refreshedInfo.options.find { it.quality == videoInfo.quality || it.label == label }
                            if (matchingOpt != null) {
                                currentUrl = if (targetFile.name.contains("audio")) matchingOpt.audioDownloadUrl ?: matchingOpt.downloadUrl else matchingOpt.downloadUrl
                            }
                        }
                        return@use
                    }

                    if (response.isSuccessful) {
                        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (len > 0) {
                            totalBytes = len
                        }
                    }
                }
                if (totalBytes > 0) break
            } catch (e: Exception) {
                Log.w(TAG, "[ChunkedDownload] Error obteniendo tamaño del stream (Intento $attempts): ${e.message}")
            }
            delay(1000L * attempts)
        }

        Log.d(TAG, "[ChunkedDownload] Stream '$label' -> Tamaño total: ${if (totalBytes > 0) "${totalBytes / (1024 * 1024)} MB ($totalBytes bytes)" else "desconocido"}")

        if (targetFile.exists()) {
            targetFile.delete()
        }

        var downloadedBytes = 0L
        var chunkIndex = 0

        while (totalBytes < 0 || downloadedBytes < totalBytes) {
            chunkIndex++
            val rangeStart = downloadedBytes
            val rangeEnd = if (totalBytes > 0) minOf(rangeStart + CHUNK_SIZE - 1, totalBytes - 1) else rangeStart + CHUNK_SIZE - 1

            var chunkSuccess = false
            var chunkAttempts = 0
            val maxChunkAttempts = 5

            while (!chunkSuccess && chunkAttempts < maxChunkAttempts) {
                chunkAttempts++
                try {
                    val sanitizedUrlLog = currentUrl.split("?")[0]
                    Log.d(TAG, "[ChunkedDownload] Trozo #$chunkIndex | '$label' | Range: bytes=$rangeStart-$rangeEnd/${if (totalBytes > 0) totalBytes.toString() else "???"} | Stream: $sanitizedUrlLog")

                    val rangeRequest = Request.Builder()
                        .url(currentUrl)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                        .addHeader("Referer", referer)
                        .addHeader("Origin", origin)
                        .addHeader("Range", "bytes=$rangeStart-$rangeEnd")
                        .get()
                        .build()

                    httpClient.newCall(rangeRequest).execute().use { response ->
                        if (response.code == 403 || response.code == 410) {
                            Log.w(TAG, "[ChunkedDownload] HTTP ${response.code} en trozo #$chunkIndex. Refrescando URLs del video...")
                            val refreshedInfo = refreshVideoUrls(videoInfo.originalUrl)
                            if (refreshedInfo != null) {
                                val matchingOpt = refreshedInfo.options.find { it.quality == videoInfo.quality || it.label == label }
                                if (matchingOpt != null) {
                                    currentUrl = if (targetFile.name.contains("audio")) matchingOpt.audioDownloadUrl ?: matchingOpt.downloadUrl else matchingOpt.downloadUrl
                                }
                            }
                            throw Exception("URL expirada o denegada (HTTP ${response.code}). Refrescando...")
                        }

                        if (!response.isSuccessful && response.code != 206) {
                            throw Exception("HTTP Error ${response.code} obteniendo trozo $rangeStart-$rangeEnd")
                        }

                        val body = response.body ?: throw Exception("Cuerpo de respuesta trozo #$chunkIndex vacío")
                        val bytesWritten = appendToFile(body.byteStream(), targetFile)

                        downloadedBytes += bytesWritten
                        chunkSuccess = true

                        val chunkProgress = if (totalBytes > 0) {
                            baseProgress + ((downloadedBytes * maxProgressRange) / totalBytes).toInt()
                        } else {
                            baseProgress + (chunkIndex * 5)
                        }

                        val currentProgress = minOf(chunkProgress, baseProgress + maxProgressRange)
                        val totalMbStr = if (totalBytes > 0) "${totalBytes / (1024 * 1024)} MB" else "?? MB"
                        val currentMbStr = "${downloadedBytes / (1024 * 1024)} MB"

                        onProgress?.invoke(
                            currentProgress,
                            "Descargando $label: $currentMbStr de $totalMbStr"
                        )

                        val contentRangeHeader = response.header("Content-Range") ?: "desconocido"
                        Log.i(TAG, "[ChunkedDownload] Trozo #$chunkIndex ÉXITO | HTTP ${response.code} | Recibidos: $bytesWritten bytes | Progreso: $currentProgress% | Content-Range: $contentRangeHeader")

                        if (response.code == 200 || bytesWritten < CHUNK_SIZE) {
                            if (totalBytes <= 0) totalBytes = downloadedBytes
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ChunkedDownload] Fallo en trozo #$chunkIndex (Intento $chunkAttempts/$maxChunkAttempts): ${e.javaClass.simpleName} - ${e.message}")
                    if (chunkAttempts >= maxChunkAttempts) {
                        throw Exception("Falló la descarga del fragmento #$chunkIndex tras $maxChunkAttempts reintentos: ${e.message}")
                    }
                    val backoffDelay = 1000L * (1 shl (chunkAttempts - 1))
                    Log.d(TAG, "[ChunkedDownload] Reintentando en ${backoffDelay / 1000}s desde el byte $downloadedBytes...")
                    delay(backoffDelay)
                }
            }

            if (totalBytes > 0 && downloadedBytes >= totalBytes) {
                break
            }
        }

        Log.i(TAG, "[ChunkedDownload] FINALIZADA DESCARGA DE STREAM '$label' -> ${targetFile.name} (${targetFile.length() / (1024 * 1024)} MB)")
    }

    private fun appendToFile(inputStream: InputStream, file: File): Long {
        var written = 0L
        FileOutputStream(file, true).use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                written += bytesRead
            }
            output.flush()
        }
        return written
    }

    private suspend fun refreshVideoUrls(originalUrl: String): VideoInfo? {
        return try {
            val repository = VideoExtractorRepository(context)
            repository.extractVideoInfo(originalUrl).getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "[RefreshUrls] Error re-extrayendo URLs del video: ${e.message}")
            null
        }
    }

    fun downloadMedia(videoInfo: VideoInfo, selectedOption: DownloadOption? = null): Long {
        val option = selectedOption ?: DownloadOption(
            label = "HD MP4",
            downloadUrl = videoInfo.downloadUrl,
            isAudio = false,
            quality = videoInfo.quality,
            extension = "mp4"
        )

        val cleanTitle = videoInfo.title.replace("[^a-zA-Z0-9_ -]".toRegex(), "_")
            .take(40)
            .ifEmpty { "media_${System.currentTimeMillis()}" }

        val ext = option.extension.lowercase().ifEmpty { if (option.isAudio) "m4a" else "mp4" }
        val fileName = "${videoInfo.platform.name.lowercase()}_${cleanTitle}_${System.currentTimeMillis()}.$ext"

        val (referer, origin) = getPlatformHeaders(videoInfo.platform)

        Log.d(TAG, "[downloadMedia] Iniciando descarga directa: Platform=${videoInfo.platform} | Label=${option.label} | Referer=$referer")

        val request = DownloadManager.Request(Uri.parse(option.downloadUrl))
            .setTitle(videoInfo.title)
            .setDescription("Descargando ${option.label} de ${videoInfo.platform.displayName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .addRequestHeader("User-Agent", USER_AGENT)
            .addRequestHeader("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
            .addRequestHeader("Referer", referer)
            .addRequestHeader("Origin", origin)

        // Imágenes (carruseles de TikTok sin marca de agua)
        if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp") {
            val mime = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            request.setMimeType(mime)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_PICTURES,
                "${SettingsManager.getDownloadSubfolder(context)}/$fileName"
            )
            val downloadId = downloadManager.enqueue(request)
            Log.i(TAG, "[downloadMedia] Imagen encolada en Pictures/VideoDownload/$fileName | ID: $downloadId")
            return downloadId
        }

        if (option.isAudio || ext == "mp3" || ext == "m4a" || ext == "webm") {
            val mime = when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "webm" -> "audio/webm"
                else -> "audio/mpeg"
            }
            request.setMimeType(mime)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MUSIC,
                "${SettingsManager.getDownloadSubfolder(context)}/$fileName"
            )
            Log.d(TAG, "[downloadMedia] Ruta destino Audio (Music/${SettingsManager.getDownloadSubfolder(context)}/$fileName) | MIME=$mime")
        } else {
            val mime = when (ext) {
                "webm" -> "video/webm"
                else -> "video/mp4"
            }
            request.setMimeType(mime)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "${SettingsManager.getDownloadSubfolder(context)}/$fileName"
            )
            Log.d(TAG, "[downloadMedia] Ruta destino Video (Movies/${SettingsManager.getDownloadSubfolder(context)}/$fileName) | MIME=$mime")
        }

        val downloadId = downloadManager.enqueue(request)
        Log.i(TAG, "[downloadMedia] Descarga encolada exitosamente con ID: $downloadId")
        return downloadId
    }

    // ====================================================================
    // DESCARGA A CARPETA PERSONALIZADA (SAF)
    // ====================================================================

    /**
     * Descarga el medio directamente en la carpeta (y almacenamiento) que el
     * usuario eligió con el selector del sistema. Devuelve la Uri del documento
     * creado. Soporta descargas DASH (video+audio por separado) usando los
     * mismos archivos temporales y muxer que la ruta clásica.
     */
    suspend fun downloadMediaSaf(
        videoInfo: VideoInfo,
        selectedOption: DownloadOption? = null,
        onProgress: ((progress: Int, statusMsg: String) -> Unit)? = null
    ): Uri = withContext(Dispatchers.IO) {
        val option = selectedOption ?: DownloadOption(
            label = "HD MP4",
            downloadUrl = videoInfo.downloadUrl,
            isAudio = false,
            quality = videoInfo.quality,
            extension = "mp4"
        )

        val treeUri = SettingsManager.getDownloadTreeUri(context)
            ?: throw Exception("No hay carpeta de descargas configurada")

        val cleanTitle = videoInfo.title.replace("[^a-zA-Z0-9_ -]".toRegex(), "_")
            .take(40)
            .ifEmpty { "media_${System.currentTimeMillis()}" }

        val ext = option.extension.lowercase().ifEmpty { if (option.isAudio) "m4a" else "mp4" }
        val fileName = "${videoInfo.platform.name.lowercase()}_${cleanTitle}_${System.currentTimeMillis()}.$ext"
        val mime = when {
            ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" -> "image/jpeg"
            option.isAudio || ext == "mp3" || ext == "m4a" -> "audio/mpeg"
            else -> "video/mp4"
        }

        // DASH: video y audio por separado -> descargar temp -> muxer -> copiar
        if (!option.isAudio && !option.audioDownloadUrl.isNullOrBlank()) {
            Log.d(TAG, "[downloadMediaSaf] Descarga DASH hacia carpeta personalizada")

            val tempVideoFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            val tempAudioFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
            val tempFinalFile = File(context.cacheDir, "final_${System.currentTimeMillis()}.mp4")

            try {
                onProgress?.invoke(5, "Iniciando descarga de video HD...")
                downloadFileChunked(
                    videoInfo = videoInfo,
                    rawUrl = option.downloadUrl,
                    targetFile = tempVideoFile,
                    label = "${option.label} (Video)",
                    baseProgress = 5,
                    maxProgressRange = 45,
                    onProgress = onProgress
                )

                onProgress?.invoke(50, "Iniciando descarga de pista de audio...")
                downloadFileChunked(
                    videoInfo = videoInfo,
                    rawUrl = option.audioDownloadUrl,
                    targetFile = tempAudioFile,
                    label = "${option.label} (Audio)",
                    baseProgress = 50,
                    maxProgressRange = 35,
                    onProgress = onProgress
                )

                onProgress?.invoke(88, "Unificando video y audio...")
                val muxed = AudioVideoMuxer.mux(tempVideoFile, tempAudioFile, tempFinalFile, option.label)
                if (!muxed || !validateDownloadedVideo(tempFinalFile)) {
                    throw Exception("El video descargado es inválido o no contiene datos de video.")
                }

                onProgress?.invoke(92, "Guardando en la carpeta elegida...")
                val docUri = copyFileToTree(treeUri, tempFinalFile, fileName, "video/mp4")

                onProgress?.invoke(100, "¡Descarga completada!")
                return@withContext docUri
            } finally {
                tempVideoFile.delete()
                tempAudioFile.delete()
                tempFinalFile.delete()
            }
        }

        // Descarga directa de un solo stream
        onProgress?.invoke(5, "Iniciando descarga...")
        val docUri = createDocumentInTree(treeUri, fileName, mime)

        val (referer, origin) = getPlatformHeaders(videoInfo.platform)

        val request = Request.Builder()
            .url(option.downloadUrl)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Referer", referer)
            .addHeader("Origin", origin)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code} al descargar el archivo")
                }

                val total = response.header("Content-Length")?.toLongOrNull() ?: -1L
                var written = 0L
                var lastReported = 5

                context.contentResolver.openOutputStream(docUri, "w")?.use { output ->
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read

                            if (total > 0) {
                                val pct = ((written * 100L) / total).toInt().coerceIn(5, 99)
                                if (pct > lastReported) {
                                    lastReported = pct
                                    onProgress?.invoke(
                                        pct,
                                        "Descargando ${option.label}: ${written / (1024 * 1024)} MB de ${total / (1024 * 1024)} MB"
                                    )
                                }
                            }
                        }
                        output.flush()
                    }
                } ?: throw Exception("No se pudo abrir la carpeta de destino")

                if (written < 50_000L) {
                    throw Exception("El archivo descargado es inválido o está vacío")
                }

                onProgress?.invoke(100, "¡Descarga completada!")
                Log.i(TAG, "[downloadMediaSaf] Archivo guardado en carpeta personalizada: $fileName ($written bytes)")
                return@withContext docUri
            }
        } catch (e: Exception) {
            // Limpieza: no dejar documentos a medio escribir
            try { DocumentsContract.deleteDocument(context.contentResolver, docUri) } catch (_: Exception) {}
            throw Exception("Error descargando a la carpeta elegida: ${e.localizedMessage}")
        }
    }

    /** Crea un documento vacío en el árbol elegido y devuelve su Uri. */
    private fun createDocumentInTree(treeUri: Uri, fileName: String, mime: String): Uri {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDir = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return DocumentsContract.createDocument(context.contentResolver, parentDir, mime, fileName)
            ?: throw Exception("No se pudo crear el archivo en la carpeta elegida")
    }

    /** Copia un archivo local al árbol SAF elegido y devuelve la Uri del documento. */
    private fun copyFileToTree(treeUri: Uri, localFile: File, fileName: String, mime: String): Uri {
        val docUri = createDocumentInTree(treeUri, fileName, mime)
        try {
            context.contentResolver.openOutputStream(docUri, "w")?.use { output ->
                localFile.inputStream().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
                output.flush()
            } ?: throw Exception("No se pudo abrir la carpeta de destino")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(context.contentResolver, docUri) } catch (_: Exception) {}
            throw e
        }
        return docUri
    }

    fun getDownloadStatus(downloadId: Long): Pair<Int, Int> {        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

            val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
            val downloaded = if (bytesDownloadedIndex >= 0) cursor.getInt(bytesDownloadedIndex) else 0
            val total = if (bytesTotalIndex >= 0) cursor.getInt(bytesTotalIndex) else 0

            cursor.close()
            return Pair(status, if (total > 0) ((downloaded * 100L) / total).toInt() else 0)
        }
        cursor?.close()
        return Pair(-1, 0)
    }
}
