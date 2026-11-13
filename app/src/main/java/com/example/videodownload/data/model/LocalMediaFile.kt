package com.example.videodownload.data.model

// Archivo multimedia real descargado por la app (video, imagen o audio)
data class LocalMediaFile(
    val name: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val contentUri: String? = null
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isVideo: Boolean
        get() = mimeType.startsWith("video/") || extension in setOf("mp4", "webm", "mkv", "3gp")

    val isImage: Boolean
        get() = mimeType.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "gif")

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/") || extension in setOf("mp3", "m4a", "aac", "ogg", "wav", "opus")

    val isPlayable: Boolean
        get() = isVideo || isAudio || isImage

    val prettySize: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024f * 1024f))
            sizeBytes >= 1024 -> "%.0f KB".format(sizeBytes / 1024f)
            else -> "$sizeBytes B"
        }
}
