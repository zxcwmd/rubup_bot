package com.rubup.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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
