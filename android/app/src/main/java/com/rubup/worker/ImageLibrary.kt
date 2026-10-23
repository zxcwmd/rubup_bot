package com.rubup.worker

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class ImageLibrary(private val context: Context, private val db: AppDatabase) {
    private val libraryDir = File(context.filesDir, "image-library").apply { mkdirs() }

    fun importImage(input: InputStream, extensionHint: String): Pair<String, ImageAsset?> {
        val bytes = readBounded(input, 20 * 1024 * 1024)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return "invalid" to null
        val mime = options.outMimeType.orEmpty().lowercase()
        val extension = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            extensionHint.lowercase() in setOf("jpg", "jpeg", "png", "webp") -> extensionHint.lowercase()
            else -> return "invalid" to null
        }
        val hash = sha256(bytes)
        val file = File(libraryDir, "$hash.$extension")
        var created = false
        if (!file.exists()) {
            val temporary = File(libraryDir, "$hash.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.delete(); return "invalid" to null
            }
            created = true
        }
        if (!db.addImage(file.absolutePath, hash)) {
            if (created) file.delete()
            return "duplicate" to null
        }
        db.requeueWaitingForImages()
        val id = db.nextImageId(file.absolutePath)
        return "added" to ImageAsset(id, file.absolutePath, hash, "available")
    }

    /** Makes a deterministic gallery copy so TikTok's own photo picker can select it. */
    fun publishForNative(asset: ImageAsset): String {
        val source = File(asset.path)
        if (!source.isFile) throw ManualActionRequired("Файл изображения не найден на рабочем телефоне")
        val displayName = "RubUP_${source.name}"
        val relativePath = "Pictures/RubUP/"
        val resolver = context.contentResolver
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=?"
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), selection,
            arrayOf(displayName), null,
        )?.use { if (it.moveToFirst()) return displayName }

        val mime = when (source.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw BotFailure("Android не разрешил подготовить фото для галереи")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw BotFailure("Не удалось открыть фото для записи в галерею")
            output.use { target -> source.inputStream().use { input -> input.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return displayName
    }

    fun uriFor(asset: ImageAsset): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.images")
        .appendPath("images")
        .appendPath(asset.fileName)
        .build()

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BotFailure("Изображение больше 20 МБ")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
