package com.rubup.worker

import java.io.File
import java.io.FileOutputStream

class LinkStore(private val file: File) {
    @Synchronized
    fun append(url: String) {
        val canonical = canonicalVideoUrl(url) ?: throw BotFailure("Отказ сохранять неканоническую ссылку")
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readLines().mapNotNull { line ->
            canonicalVideoUrl(line.trim()) ?: line.trim().ifBlank { null }
        }.toSet() else emptySet()
        if (canonical in existing) return
        FileOutputStream(file, true).use { output ->
            output.write((canonical + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    fun lines(): List<String> = if (file.isFile) file.readLines().map(String::trim).filter(String::isNotEmpty) else emptyList()
}
