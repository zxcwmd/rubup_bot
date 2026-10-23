package com.rubup.worker

import java.security.MessageDigest

enum class ActionType(val dbValue: String) {
    COMMENT("comment"), REPLY("reply")
}

enum class ActionStatus(val dbValue: String) {
    PENDING("pending"), PUBLISHING("publishing"), PUBLISHED("published"),
    MANUAL_REVIEW("manual_review"), FAILED("failed"), SKIPPED("skipped")
}

enum class LikeStatus(val dbValue: String) {
    PENDING("pending"), PUBLISHING("publishing"), LIKED("liked"),
    MANUAL_REVIEW("manual_review"), FAILED("failed")
}

data class Video(
    val id: String,
    val url: String,
    val author: String = "",
    val description: String = "",
    val publishedAt: Long? = null,
    val matchedTags: Set<String> = emptySet(),
)

data class TikTokComment(
    val id: String,
    val videoId: String,
    val author: String,
    val text: String,
    val hasImage: Boolean = false,
    val domIndex: Int = -1,
    val isOwn: Boolean = false,
)

data class ImageAsset(
    val id: Long,
    val path: String,
    val sha256: String,
    val status: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

data class ActionItem(
    val id: Long,
    val videoId: String,
    val videoUrl: String,
    val actionType: ActionType,
    val targetCommentId: String? = null,
    val targetAuthor: String = "",
    val targetText: String = "",
    val targetDomIndex: Int = -1,
    val text: String,
    val normalizedText: String,
    val imageId: Long? = null,
    val imagePath: String? = null,
    val status: ActionStatus,
    val likeStatus: LikeStatus? = null,
    val linkSavedAt: Long? = null,
    val error: String = "",
)

object Defaults {
    val TAGS = listOf(
        "upgrader", "cs2", "caser", "rubup", "кейсер", "апгрейдер",
        "тгподарки", "подаркитг", "нфт", "nft", "тг", "телеграм", "telegram",
        "меллстрой", "меллстройнарезки", "mellstroy", "мелстрой", "dota2",
        "twitch", "leva2k", "buster", "лева2к", "бустер", "твич", "gensyxa",
        "генсуха", "нарезка",
    )
    const val MIN_VIDEO_DATE = "2026-05-01"
    const val DISCLOSURE = "Реклама"
    const val MAX_TAGS_PER_CYCLE = 4
    const val DISCOVERY_LIMIT = 8
    const val MAX_DETAILS_PER_CYCLE = 20
    const val MAX_ACTIONS_PER_CYCLE = 4
    const val MAX_COMMENTS_PER_DAY = 10
    const val MAX_REPLIES_PER_DAY = 20
    const val MAX_REPLIES_PER_VIDEO = 2
    const val ACTION_DELAY_SECONDS = 20L
    const val ACTION_JITTER_SECONDS = 15L
    const val SCAN_INTERVAL_MINUTES = 45L
    const val SIMILARITY_THRESHOLD = 0.86
    const val TARGET_SIMILARITY_THRESHOLD = 0.92
    val RUBUP_MARKERS = Regex("rubup|rub\\s*up", RegexOption.IGNORE_CASE)
}

open class BotFailure(message: String) : Exception(message)
class SessionRequired(message: String) : BotFailure(message)
class ChallengeRequired(message: String) : BotFailure(message)
class AutomationStopped(message: String) : BotFailure(message)
class ManualActionRequired(message: String, val mayHavePublished: Boolean = false) : BotFailure(message)

fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }

fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

fun stableCommentId(videoId: String, author: String, text: String): String =
    "local-" + sha256("$videoId\u0000${author.lowercase().removePrefix("@")}\u0000${normalizeText(text)}").take(24)
