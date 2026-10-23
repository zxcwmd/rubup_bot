package com.rubup.worker

import android.content.Context
import java.text.Normalizer
import kotlin.math.max
import kotlin.random.Random

private val URL_RE = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val WORD_RE = Regex("[a-zа-я0-9]+", RegexOption.IGNORE_CASE)
private val BRAND_RE = Regex("\\brub\\s*up\\b", RegexOption.IGNORE_CASE)
private val GIFT_RE = Regex(
    "(спасибо|спс|благодар).*(подар|скин|мишк|кейс|бот)|(выдал|получил|пришл).*(подар|скин|мишк|кейс)",
    RegexOption.IGNORE_CASE,
)
private val DISCLOSURE_RE = Regex("(?:#?\\bреклама\\b|#?\\bad\\b|\\bsponsored\\b)", RegexOption.IGNORE_CASE)

fun normalizeText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace('ё', 'е')
    return WORD_RE.findAll(URL_RE.replace(normalized, " ")).joinToString(" ") { it.value }
}

private fun levenshtein(left: String, right: String): Int {
    var previous = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = i + 1
        for (j in right.indices) {
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}

fun similarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    if (left == right) return 1.0
    val edit = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
    val a = left.split(' ').filter(String::isNotBlank).toSet()
    val b = right.split(' ').filter(String::isNotBlank).toSet()
    val union = a union b
    val jaccard = if (union.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / union.size
    return max(edit.coerceAtLeast(0.0), jaccard)
}

fun topicFor(video: Video): String {
    val haystack = "${video.description} ${video.matchedTags.joinToString(" ")}".lowercase()
    val topics = listOf(
        listOf("меллстрой", "мелстрой", "mellstroy", "генсуха", "gensyxa") to "эту нарезку",
        listOf("dota2", "дота") to "Dota 2",
        listOf("twitch", "твич", "leva2k", "лева2к", "buster", "бустер") to "этот стрим",
        listOf("тгподарки", "подаркитг", "telegram", "телеграм", "нфт", "nft") to "подарки в Telegram",
        listOf("upgrader", "апгрейдер", "caser", "кейсер", "cs2", "кейс") to "кейсы и апгрейды",
    )
    topics.firstOrNull { (markers, _) -> markers.any(haystack::contains) }?.let { return it.second }
    val description = video.description
        .replace(Regex("https?://\\S+|#[\\w_]+|@[\\w_]+"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '.', ',', '!', '?', ':', ';', '-')
    return if (description.length in 4..70) "тему «$description»" else "этот ролик"
}

fun withDisclosure(text: String): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    return if (DISCLOSURE_RE.containsMatchIn(clean)) clean else "$clean ${Defaults.DISCLOSURE}".trim()
}

class TextEngine(private val context: Context) {
    private fun templates(type: ActionType): List<String> {
        val name = if (type == ActionType.COMMENT) "templates/comments.txt" else "templates/replies.txt"
        return context.assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        }
    }

    fun generate(
        video: Video,
        type: ActionType,
        forbiddenTexts: List<String>,
        targetAuthor: String = "",
    ): String {
        val forbidden = forbiddenTexts.filter(String::isNotBlank).map(::normalizeText)
        val topic = topicFor(video)
        val candidates = templates(type).shuffled(Random.Default)
        for (template in candidates) {
            repeat(3) { variant ->
                val suffix = when (variant) {
                    0 -> ""; 1 -> ")"; else -> " 🙂"
                }
                var text = template.replace("{topic}", topic).replace("{author}", targetAuthor).trim() + suffix
                if (BRAND_RE.containsMatchIn(text) || GIFT_RE.containsMatchIn(text)) return@repeat
                text = withDisclosure(text)
                if (text.length !in 4..220) return@repeat
                val normalized = normalizeText(text)
                if (normalized.isBlank()) return@repeat
                if (forbidden.any { similarity(normalized, it) >= Defaults.SIMILARITY_THRESHOLD }) return@repeat
                return text
            }
        }
        throw BotFailure("Не осталось уникальных шаблонов. Добавьте новые тексты в исходники приложения.")
    }
}

fun isOwn(comment: TikTokComment, username: String): Boolean =
    comment.isOwn || (username.isNotBlank() && comment.author.lowercase().removePrefix("@").trim() == username)

fun replyAllowed(comment: TikTokComment, username: String, previousTargets: List<String>): Boolean {
    if (isOwn(comment, username)) return false
    if (!Defaults.RUBUP_MARKERS.containsMatchIn(comment.text)) return false
    val normalized = normalizeText(comment.text)
    if (normalized.isBlank()) return false
    return previousTargets.none {
        similarity(normalized, normalizeText(it)) >= Defaults.TARGET_SIMILARITY_THRESHOLD
    }
}
