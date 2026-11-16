package com.example.videodownload.ui

import android.app.Application
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodownload.data.model.DownloadOption
import com.example.videodownload.data.model.DownloadUiState
import com.example.videodownload.data.model.DownloadedItem
import com.example.videodownload.data.model.LocalMediaFile
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.data.repository.VideoExtractorRepository
import com.example.videodownload.downloader.AndroidVideoDownloader
import com.example.videodownload.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoExtractorRepository(application)
    private val downloader = AndroidVideoDownloader(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _uiState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private val _detectedPlatform = MutableStateFlow(Platform.UNKNOWN)
    val detectedPlatform: StateFlow<Platform> = _detectedPlatform.asStateFlow()

    private val _historyList = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val historyList: StateFlow<List<DownloadedItem>> = _historyList.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<List<LocalMediaFile>>(emptyList())
    val downloadedFiles: StateFlow<List<LocalMediaFile>> = _downloadedFiles.asStateFlow()

    init {
        refreshDownloadedFiles()
    }

    // Busca en la galería (MediaStore) los archivos guardados en la carpeta VideoDownload
    fun refreshDownloadedFiles() {
        viewModelScope.launch {
            _downloadedFiles.value = queryDownloadedFiles()
        }
    }

    private suspend fun queryDownloadedFiles(): List<LocalMediaFile> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val files = mutableListOf<LocalMediaFile>()
        // Colecciones con su Uri base para construir content:// URIs (miniaturas y reproducción)
        val collections = listOf(
            MediaStore.Video.Media.getContentUri("external"),
            MediaStore.Audio.Media.getContentUri("external"),
            MediaStore.Images.Media.getContentUri("external")
        )

        for (baseUri in collections) {
            try {
                app.contentResolver.query(
                    baseUri,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED
                    ),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("%${SettingsManager.getDownloadSubfolder(app)}%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        val path = cursor.getString(dataIdx) ?: continue
                        val id = cursor.getLong(idIdx)
                        files.add(
                            LocalMediaFile(
                                name = cursor.getString(nameIdx) ?: path.substringAfterLast('/'),
                                path = path,
                                mimeType = cursor.getString(mimeIdx) ?: "",
                                sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                                dateAdded = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                                contentUri = baseUri.buildUpon().appendPath(id.toString()).toString()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        files.sortedByDescending { it.dateAdded }

        // Sumar archivos de la carpeta personalizada (SAF) si hay una configurada
        val treeUri = SettingsManager.getDownloadTreeUri(app)
        if (treeUri != null) {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = android.provider.DocumentsContract
                    .buildChildDocumentsUriUsingTree(treeUri, docId)

                app.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docIdCol = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2) ?: continue
                        val size = cursor.getLong(3)
                        val lastMod = cursor.getLong(4)

                        // Solo archivos multimedia (ignora directorios y otros)
                        val isMedia = mime.startsWith("video/") ||
                                mime.startsWith("audio/") ||
                                mime.startsWith("image/")
                        if (!isMedia || name.startsWith(".")) continue

                        val docUri = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, docIdCol)

                        files.add(
                            LocalMediaFile(
                                name = name,
                                path = name,
                                mimeType = mime,
                                sizeBytes = size,
                                dateAdded = lastMod / 1000,
                                contentUri = docUri.toString()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "No se pudo leer la carpeta personalizada: ${e.message}")
            }
        }

        // Deduplicar por nombre (por si MediaStore también indexó la carpeta SAF)
        val seen = mutableSetOf<String>()
        files
            .sortedByDescending { it.dateAdded }
            .filter { seen.add(it.name) }
    }

    fun onUrlInputChanged(newUrl: String) {
        _urlInput.value = newUrl
        _detectedPlatform.value = repository.detectPlatform(newUrl)
        if (newUrl.isBlank()) {
            _uiState.value = DownloadUiState.Idle
        }
    }

    fun pasteFromClipboard() {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                onUrlInputChanged(text)
                fetchVideoInfo()
            }
        }
    }

    fun handleSharedUrl(sharedText: String) {
        if (sharedText.isNotBlank()) {
            onUrlInputChanged(sharedText)
            fetchVideoInfo()
        }
    }

    fun fetchVideoInfo() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) {
            _uiState.value = DownloadUiState.Error("Ingresa o pega un enlace de video válido.")
            return
        }

        viewModelScope.launch {
            _uiState.value = DownloadUiState.Loading("Analizando enlace y obteniendo calidades disponibles...")
            val result = repository.extractVideoInfo(url)

            result.onSuccess { info ->
                _uiState.value = DownloadUiState.InfoLoaded(info)
            }.onFailure { exception ->
                _uiState.value = DownloadUiState.Error(
                    exception.localizedMessage ?: "No se pudo procesar el enlace. Revisa que sea público."
                )
            }
        }
    }

    fun downloadMedia(videoInfo: VideoInfo, option: DownloadOption? = null) {
        viewModelScope.launch {
            try {
                val label = option?.label ?: videoInfo.quality

                // Carpeta personalizada (SAF): descarga manual con progreso,
                // guardando directamente en el almacenamiento elegido.
                if (SettingsManager.getDownloadTreeUri(getApplication()) != null) {
                    _uiState.value = DownloadUiState.Downloading(2, "Iniciando descarga...")
                    try {
                        downloader.downloadMediaSaf(videoInfo, option) { progressPercent, statusMsg ->
                            _uiState.value = DownloadUiState.Downloading(
                                progress = progressPercent,
                                title = statusMsg
                            )
                        }

                        _uiState.value = DownloadUiState.Success(
                            videoInfo = videoInfo,
                            filePath = "Carpeta personalizada"
                        )

                        val newItem = DownloadedItem(
                            id = videoInfo.id,
                            title = "${videoInfo.title} [${label}]",
                            platform = videoInfo.platform,
                            localUri = option?.downloadUrl ?: videoInfo.downloadUrl
                        )
                        _historyList.value = listOf(newItem) + _historyList.value
                        refreshDownloadedFiles()
                    } catch (e: Exception) {
                        _uiState.value = DownloadUiState.Error("Error descargando: ${e.localizedMessage}")
                    }
                    return@launch
                }

                _uiState.value = DownloadUiState.Downloading(2, "Iniciando descarga...")

                if (option?.audioDownloadUrl != null && !option.isAudio) {
                    downloader.downloadMediaAsync(videoInfo, option) { progressPercent, statusMsg ->
                        _uiState.value = DownloadUiState.Downloading(
                            progress = progressPercent,
                            title = statusMsg
                        )
                    }

                    val path = "Movies/${SettingsManager.getDownloadSubfolder(getApplication())}/"
                    _uiState.value = DownloadUiState.Success(
                        videoInfo = videoInfo,
                        filePath = path
                    )

                    val newItem = DownloadedItem(
                        id = videoInfo.id,
                        title = "${videoInfo.title} [${option.label}]",
                        platform = videoInfo.platform,
                        localUri = option.downloadUrl
                    )
                    _historyList.value = listOf(newItem) + _historyList.value
                    refreshDownloadedFiles()
                    return@launch
                }

                val downloadId = downloader.downloadMedia(videoInfo, option)

                var isComplete = false
                while (!isComplete) {
                    delay(500)
                    val (status, progress) = downloader.getDownloadStatus(downloadId)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isComplete = true
                            val imageExt = setOf("jpg", "jpeg", "png", "webp")
                            val optExt = option?.extension?.lowercase()
                            val sub = SettingsManager.getDownloadSubfolder(getApplication())
                            val path = when {
                                optExt != null && optExt in imageExt -> "Pictures/$sub/"
                                option?.isAudio == true -> "Music/$sub/"
                                else -> "Movies/$sub/"
                            }
                            _uiState.value = DownloadUiState.Success(
                                videoInfo = videoInfo,
                                filePath = path
                            )

                            val newItem = DownloadedItem(
                                id = videoInfo.id,
                                title = "${videoInfo.title} [${option?.label ?: "HD"}]",
                                platform = videoInfo.platform,
                                localUri = option?.downloadUrl ?: videoInfo.downloadUrl
                            )
                            _historyList.value = listOf(newItem) + _historyList.value
                            refreshDownloadedFiles()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isComplete = true
                            _uiState.value = DownloadUiState.Error("La descarga falló. Intenta de nuevo.")
                        }
                        else -> {
                            val currentProgress = if (progress in 1..99) progress else 15
                            _uiState.value = DownloadUiState.Downloading(
                                progress = currentProgress,
                                title = "Descargando ${videoInfo.title} (${option?.label ?: "HD"})..."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = DownloadUiState.Error("Error iniciando la descarga: ${e.localizedMessage}")
            }
        }
    }

    fun resetState() {
        _uiState.value = DownloadUiState.Idle
        _urlInput.value = ""
        _detectedPlatform.value = Platform.UNKNOWN
    }
}
