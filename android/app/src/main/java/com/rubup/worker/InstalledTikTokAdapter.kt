package com.rubup.worker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Launches only the TikTok package already installed by the user. */
object TikTokAppLauncher {
    val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")

    fun installedPackage(context: Context): String? {
        val manager = context.packageManager
        packages.firstOrNull { manager.getLaunchIntentForPackage(it) != null }?.let { return it }
        // Some regional/Lite/clone builds use another package name. Detect only
        // launcher activities whose label or package is explicitly TikTok-related.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = manager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        return candidates.firstOrNull { resolve ->
            val info = resolve.activityInfo ?: return@firstOrNull false
            val packageName = info.packageName.orEmpty()
            val label = try { resolve.loadLabel(manager).toString() } catch (_: Exception) { "" }
            label.contains("tiktok", true) || packageName.contains("tiktok", true) ||
                packageName.contains("musically", true) || packageName.contains("trill", true)
        }?.activityInfo?.packageName
    }

    fun open(context: Context, url: String? = null): String {
        val packageName = installedPackage(context)
            ?: throw SessionRequired(
                "TikTok не найден. Установите и включите официальный TikTok в том же профиле Android, где установлен RubUP Worker.",
            )
        val intent = if (url == null) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName)
        } ?: throw SessionRequired("TikTok установлен, но Android не может его открыть")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            throw BotFailure("TikTok не открыл ссылку в установленном приложении: ${error.message}")
        }
        return packageName
    }
}

fun isTikTokCommentEntry(node: UiNode): Boolean {
    if (!node.visible || node.editable) return false
    val label = node.textOrDescription().trim().lowercase(Locale.ROOT)
    val id = node.resourceId.lowercase(Locale.ROOT)
    val visual = node.className.contains("button", true) || node.className.contains("image", true) ||
        id.contains("comment") || (node.clickable && node.text.isBlank())
    return visual && (label.contains("comment") || label.contains("коммент") || id.contains("comment"))
}

fun isTikTokCommentInput(node: UiNode): Boolean = node.visible && node.editable &&
    (node.label().contains("comment", true) || node.label().contains("коммент", true) ||
        node.resourceId.contains("reply", true))

private data class HttpPage(val finalUrl: String, val body: String)

/**
 * Driver for the real TikTok Android application. Public HTTPS pages are used
 * only for discovery/metadata; comments, photos and likes are performed in the app.
 */
class InstalledTikTokAdapter(
    private val context: Context,
    private val settings: SettingsStore,
    private val images: ImageLibrary,
) : TikTokOperations {
    private var currentVideoUrl: String? = null
    var progressListener: ((String) -> Unit)? = null

    private fun progress(text: String) {
        progressListener?.invoke(text)
    }

    private fun accessibility(): TikTokAccessibilityService {
        if (!TikTokAccessibilityService.isReady()) {
            throw SessionRequired(
                "Включите RubUP Worker в Настройки → Спецвозможности. Без этого Android не разрешает управлять TikTok.",
            )
        }
        return TikTokAccessibilityService.current()
            ?: throw SessionRequired("Accessibility-служба RubUP Worker ещё не подключена")
    }

    override fun ensureAuthenticated() {
        val packageName = TikTokAppLauncher.installedPackage(context)
            ?: throw SessionRequired("TikTok не найден. Проверьте, что он установлен и включён в том же профиле Android, что и RubUP Worker")
        val service = accessibility()
        TikTokAppLauncher.open(context)
        if (!service.waitForPackage(setOf(packageName), 20)) {
            throw SessionRequired("TikTok не стал активным приложением. Откройте его вручную и повторите проверку")
        }
        Thread.sleep(600)
        val text = service.snapshot().text().lowercase(Locale.ROOT)
        checkChallengeText(text)
        val loginScreen = Regex("log in to tiktok|sign up for tiktok|войдите в tiktok|войти в tiktok|создать аккаунт")
            .containsMatchIn(text)
        val normalScreen = Regex("for you|following|для вас|подписки|профиль").containsMatchIn(text)
        if (loginScreen && !normalScreen) {
            throw SessionRequired("В установленном TikTok нет входа. Войдите в один аккаунт вручную")
        }
    }

    override fun discover(tag: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val clean = tag.removePrefix("#")
        val page = fetch("https://www.tiktok.com/tag/${Uri.encode(clean)}")
        val readable = readable(page.body)
        val found = linkedSetOf<String>()
        val absolute = Regex("https?://(?:www\\.)?tiktok\\.com/@[^\\s\"'<\\\\]+/video/\\d+")
        val relative = Regex("/@[^\\s\"'<\\\\]+/video/\\d+")
        absolute.findAll(readable).forEach { match ->
            canonicalVideoUrl(match.value)?.let(found::add)
            if (found.size >= limit) return found.toList()
        }
        relative.findAll(readable).forEach { match ->
            canonicalVideoUrl("https://www.tiktok.com${match.value}")?.let(found::add)
            if (found.size >= limit) return found.toList()
        }
        return found.take(limit)
    }

    override fun getVideo(url: String, sourceTag: String): Video {
        val page = fetch(url)
        val readable = readable(page.body)
        val canonical = canonicalVideoUrl(url)
            ?: canonicalVideoUrl(page.finalUrl)
            ?: findCanonical(readable)
            ?: throw BotFailure("TikTok не вернул каноническую ссылку на видео")
        val videoId = videoIdFromUrl(canonical) ?: throw BotFailure("Не удалось прочитать ID видео")
        var published = extractNumber(readable, "createTime") ?: extractNumber(readable, "create_time")
        if (published != null && published > 10_000_000_000L) published /= 1000
        var author = extractString(readable, "uniqueId")
        if (author.isBlank()) author = extractString(readable, "unique_id")
        if (author.isBlank()) author = Uri.parse(canonical).pathSegments.firstOrNull().orEmpty().removePrefix("@")
        val description = extractString(readable, "desc").trim()
        val tags = Regex("#([\\p{L}\\p{N}_]+)").findAll(description)
            .map { it.groupValues[1].lowercase() }.toMutableSet()
        if (sourceTag.isNotBlank()) tags += sourceTag.removePrefix("#").lowercase()
        return Video(videoId, canonical, author, description, published, tags)
    }

    override fun getComments(videoUrl: String, limit: Int): List<TikTokComment> {
        val canonical = canonicalVideoUrl(videoUrl) ?: throw BotFailure("Нужна каноническая ссылка на видео")
        ensureVideo(canonical)
        openComments()
        return readComments(canonical, limit)
    }

    override fun postComment(action: ActionItem, image: ImageAsset?, imageUri: Uri?, stopCheck: () -> Unit) {
        val canonical = canonicalVideoUrl(action.videoUrl) ?: throw BotFailure("Неканоническая ссылка")
        ensureVideo(canonical)
        openComments()
        val service = accessibility()
        if (action.actionType == ActionType.REPLY) {
            stopCheck()
            val container = findContainer(service.snapshot(), action.targetText, action.targetAuthor)
                ?: throw ManualActionRequired("Не найден чужой комментарий для ответа")
            val reply = container.flatten().firstOrNull { isReplyNode(it) }
                ?: throw ManualActionRequired("Кнопка ответа в TikTok не найдена")
            if (!service.clickPath(reply.path)) throw ManualActionRequired("Не удалось нажать кнопку ответа")
            Thread.sleep(500)
        }
        if (action.actionType == ActionType.COMMENT) {
            if (image == null) throw ManualActionRequired("Для основного комментария обязательна новая картинка")
            stopCheck()
            progress("Действие #${action.id}: прикрепляю обязательное фото…")
            attachPhoto(image)
        }
        progress("Действие #${action.id}: ввожу текст комментария…")
        val input = service.snapshot().nodes.firstOrNull(::isTikTokCommentInput)
            ?: throw ManualActionRequired("Поле комментария в TikTok не найдено или не распознано")
        if (!service.setTextPath(input.path, action.text)) {
            throw ManualActionRequired("Android не разрешил ввести текст в поле комментария")
        }
        Thread.sleep(400)
        val filled = service.snapshot().nodes.firstOrNull(::isTikTokCommentInput)
        val actualText = filled?.text?.trim().orEmpty()
        if (actualText != action.text.trim() && normalizeText(actualText) != normalizeText(action.text)) {
            throw ManualActionRequired("TikTok не подтвердил введённый текст; публикация не нажималась")
        }
        stopCheck()
        val ready = if (action.actionType == ActionType.COMMENT) "фото и текст готовы" else "текст ответа готов"
        progress("Действие #${action.id}: $ready, нажимаю публикацию…")
        try {
            if (!clickSubmit()) throw ManualActionRequired("Кнопка публикации в TikTok не найдена или неактивна")
        } catch (error: ManualActionRequired) {
            throw error
        } catch (error: Exception) {
            throw ManualActionRequired("Нажатие публикации не подтверждено: ${error.message}", true)
        }
        progress("Действие #${action.id}: нажатие выполнено, проверяю опубликованный комментарий…")
        try {
            Thread.sleep(2800)
            checkChallenge()
            val expected = normalizeText(action.text)
            val own = settings.tiktokUsername
            val verified = getComments(canonical, 120).any {
                normalizeText(it.text) == expected && it.author.lowercase().removePrefix("@") == own &&
                    (action.actionType == ActionType.REPLY || it.hasImage)
            }
            if (!verified) {
                throw ManualActionRequired(
                    "Нажатие выполнено, но свой комментарий с обязательным фото не подтверждён. Проверьте вручную перед повтором.",
                    true,
                )
            }
        } catch (error: ManualActionRequired) {
            throw error
        } catch (error: ChallengeRequired) {
            throw ManualActionRequired("После нажатия TikTok показал проверку: ${error.message}", true)
        } catch (error: Exception) {
            throw ManualActionRequired("После нажатия не удалось проверить результат: ${error.message}", true)
        }
    }

    override fun likeOwnComment(action: ActionItem, stopCheck: () -> Unit): Boolean {
        if (settings.tiktokUsername.isBlank()) return false
        val canonical = canonicalVideoUrl(action.videoUrl) ?: return false
        val expected = normalizeText(action.text)
        ensureVideo(canonical)
        openComments()
        val service = accessibility()
        val initial = findContainer(service.snapshot(), expected, settings.tiktokUsername, normalized = true)
            ?: return false
        if (isLiked(initial)) return true
        val like = initial.flatten().firstOrNull { isLikeNode(it) } ?: return false
        stopCheck()
        progress("Действие #${action.id}: нажимаю лайк именно своего комментария…")
        val clicked = try { service.clickPath(like.path) } catch (_: Exception) { return false }
        if (!clicked) return false
        return try {
            Thread.sleep(800)
            val current = findContainer(service.snapshot(), expected, settings.tiktokUsername, normalized = true)
            if (current != null && isLiked(current)) {
                true
            } else {
                openVideo(canonical)
                openComments()
                val refreshed = findContainer(service.snapshot(), expected, settings.tiktokUsername, normalized = true)
                refreshed != null && isLiked(refreshed)
            }
        } catch (_: Exception) {
            // Never click a second time when the first click's result is unknown.
            false
        }
    }

    private fun ensureVideo(canonical: String) {
        if (currentVideoUrl == canonical && accessibility().currentPackage() == TikTokAppLauncher.installedPackage(context)) return
        openVideo(canonical)
    }

    private fun openVideo(canonical: String) {
        val service = accessibility()
        progress("TikTok: открываю видео\n$canonical")
        val packageName = TikTokAppLauncher.open(context, canonical)
        if (!service.waitForPackage(setOf(packageName), 25)) {
            throw BotFailure("TikTok не открыл видео в установленном приложении")
        }
        Thread.sleep(1000)
        currentVideoUrl = canonical
        checkChallenge()
    }

    private fun openComments() {
        val service = accessibility()
        checkChallenge()
        if (service.snapshot().nodes.any(::isTikTokCommentInput)) return
        progress("TikTok: открываю панель комментариев…")
        if (!service.clickFirst(::isTikTokCommentEntry)) {
            throw ManualActionRequired("Кнопка комментариев в открытом видео не распознана")
        }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            if (service.snapshot().nodes.any(::isTikTokCommentInput)) return
        }
        checkChallenge()
        throw ManualActionRequired("Кнопка комментариев нажата, но поле ввода не появилось или не распознано")
    }

    private fun readComments(canonical: String, limit: Int): List<TikTokComment> {
        if (limit <= 0) return emptyList()
        val snapshot = accessibility().snapshot()
        val containers = linkedMapOf<String, UiNode>()
        snapshot.nodes.filter { it.visible && it.text.isNotBlank() }.forEach { node ->
            nearestCommentContainer(snapshot, node)?.let { containers[it.path] = it }
        }
        val videoId = videoIdFromUrl(canonical) ?: return emptyList()
        val result = mutableListOf<TikTokComment>()
        for (container in containers.values) {
            val body = commentBody(container) ?: continue
            val text = body.text.trim()
            val author = authorFrom(container, body) ?: continue
            val id = stableCommentId(videoId, author, text)
            if (result.any { it.id == id }) continue
            result += TikTokComment(
                id = id,
                videoId = videoId,
                author = author,
                text = text,
                hasImage = container.flatten().any(::isPhotoEvidence),
                domIndex = result.size,
                isOwn = author.lowercase().removePrefix("@") == settings.tiktokUsername,
            )
            if (result.size >= limit) break
        }
        return result
    }

    private fun nearestCommentContainer(snapshot: UiSnapshot, node: UiNode): UiNode? =
        snapshot.ancestors(node).firstOrNull { candidate ->
            val all = candidate.flatten()
            all.size in 5..90 &&
                all.count { it.visible && it.text.isNotBlank() } >= 2 &&
                all.any(::isLikeNode)
        }

    private fun commentBody(container: UiNode): UiNode? {
        val candidates = container.flatten().filter { node ->
            node.visible && node.text.trim().length >= 2 && !isUiLabel(node.text) &&
                !isLikeNode(node) && !isReplyNode(node) && !looksLikeTime(node.text) &&
                !looksLikeUsername(node.text)
        }
        return candidates.sortedByDescending { node ->
            val id = node.resourceId.lowercase(Locale.ROOT)
            (if (id.contains("comment") || id.contains("content") || id.contains("message")) 1000 else 0) + node.text.length
        }.firstOrNull()
    }

    private fun authorFrom(container: UiNode, body: UiNode): String? {
        val all = container.flatten()
        val explicit = all.firstOrNull { node ->
            val id = node.resourceId.lowercase(Locale.ROOT)
            node.visible && node.text.isNotBlank() &&
                (id.contains("author") || id.contains("username") || id.contains("user_name"))
        }
        val candidates = if (explicit != null) listOf(explicit) else all.filter { node ->
            node.visible && node.path != body.path && node.text.isNotBlank() &&
                !isUiLabel(node.text) && !looksLikeTime(node.text) &&
                (node.text.trim().startsWith("@") || looksLikeUsername(node.text))
        }
        return candidates.map { it.text.trim().removePrefix("@") }
            .firstOrNull { it.length in 2..40 && !it.equals(body.text.trim(), true) }
    }

    private fun findContainer(
        snapshot: UiSnapshot,
        text: String,
        author: String,
        normalized: Boolean = false,
    ): UiNode? {
        val expected = if (normalized) text else normalizeText(text)
        val expectedAuthor = author.lowercase().removePrefix("@")
        return snapshot.nodes.filter { it.visible && it.text.isNotBlank() }
            .firstNotNullOfOrNull { node ->
                val nodeText = normalizeText(node.text)
                if (nodeText != expected && !nodeText.contains(expected)) return@firstNotNullOfOrNull null
                val container = nearestCommentContainer(snapshot, node) ?: return@firstNotNullOfOrNull null
                val actual = readCommentsFromContainer(container) ?: return@firstNotNullOfOrNull null
                val authorMatches = expectedAuthor.isBlank() || actual.first.equals(expectedAuthor, true)
                if (authorMatches) container else null
            }
    }

    private fun readCommentsFromContainer(container: UiNode): Pair<String, String>? {
        val body = commentBody(container) ?: return null
        val author = authorFrom(container, body) ?: return null
        return author.lowercase().removePrefix("@") to body.text
    }

    private fun attachPhoto(image: ImageAsset) {
        val service = accessibility()
        val displayName = images.publishForNative(image)
        val clicked = service.clickFirst { node ->
            val label = node.label().lowercase(Locale.ROOT)
            !node.editable && (label.contains("photo") || label.contains("picture") || label.contains("gallery") ||
                label.contains("фото") || label.contains("галере") || label.contains("изображ"))
        }
        if (!clicked) throw ManualActionRequired("В TikTok не найдена кнопка добавления фото")
        Thread.sleep(1300)
        val selected = selectGalleryImage(displayName)
        if (!selected) throw ManualActionRequired("Не удалось выбрать именно $displayName; случайное фото не используется")
        Thread.sleep(1200)
        service.clickFirst { node ->
            node.textOrDescription().trim().lowercase(Locale.ROOT) in
                setOf("open", "done", "select", "add", "готово", "выбрать", "открыть", "добавить")
        }
        Thread.sleep(800)
        val packageName = TikTokAppLauncher.installedPackage(context)
        val preview = service.snapshot().nodes.any { node ->
            val label = node.label().lowercase(Locale.ROOT)
            service.currentPackage() == packageName &&
                (label.contains("remove photo") || label.contains("delete photo") || label.contains("photo added") ||
                    label.contains("удалить фото") || label.contains("фото добавлено") ||
                    (node.resourceId.contains("comment", true) &&
                        (node.resourceId.contains("photo", true) || node.resourceId.contains("image", true))))
        }
        if (!preview) throw ManualActionRequired("Фото не подтверждено предпросмотром в TikTok; публикация не отправлена")
    }

    private fun selectGalleryImage(displayName: String): Boolean {
        val service = accessibility()
        if (service.clickFirst { it.label().contains(displayName, true) }) return true
        val hash = displayName.substringAfter("RubUP_").substringBefore('.')
        if (service.clickFirst { it.label().contains(hash, true) }) return true
        if (service.clickFirst { it.label().contains("RubUP", true) }) {
            Thread.sleep(800)
            return service.clickFirst { it.label().contains(displayName, true) || it.label().contains(hash, true) }
        }
        return false
    }

    private fun clickSubmit(): Boolean = accessibility().clickFirst { node ->
        val label = node.textOrDescription().trim().lowercase(Locale.ROOT)
        val id = node.resourceId.lowercase(Locale.ROOT)
        label in setOf("post", "publish", "send", "опубликовать", "отправить") ||
            label.startsWith("post ") || label.startsWith("publish ") || label.startsWith("send ") ||
            label.startsWith("опубликовать ") || label.startsWith("отправить ") ||
            (id.contains("comment") && (id.contains("post") || id.contains("send") || id.contains("publish")))
    }

    private fun isLiked(container: UiNode): Boolean = container.flatten().filter(::isLikeNode).any { node ->
        val label = node.label().lowercase(Locale.ROOT)
        node.selected || node.checked || label.contains("unlike") || label.contains("не нравится")
    }

    private fun checkChallenge() = checkChallengeText(accessibility().snapshot().text().lowercase(Locale.ROOT))

    private fun checkChallengeText(text: String) {
        if (Regex("captcha|verify you are human|подтвердите, что вы не робот|проверка безопасности").containsMatchIn(text)) {
            throw ChallengeRequired("TikTok показал проверку безопасности. Пройдите её вручную в установленном приложении")
        }
    }

    private fun fetch(url: String): HttpPage {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "tiktok.com" && !host.endsWith(".tiktok.com")) throw BotFailure("Разрешены только ссылки TikTok")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw BotFailure("TikTok HTTP $code при получении метаданных")
            val stream = connection.inputStream
            val body = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 6 * 1024 * 1024) throw BotFailure("Ответ TikTok слишком большой")
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            HttpPage(connection.url.toString(), body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readable(html: String): String = html
        .replace("\\u002F", "/").replace("\\u002f", "/")
        .replace("\\/", "/").replace("\\\"", "\"")

    private fun findCanonical(html: String): String? {
        val match = Regex("https?://(?:www\\.)?tiktok\\.com/@[^\\s\"'<\\\\]+/video/\\d+").find(html)
        return match?.value?.let(::canonicalVideoUrl)
    }

    private fun extractNumber(html: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*\"?(\\d{10,13})").find(html)?.groupValues?.get(1)?.toLongOrNull()

    private fun extractString(html: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(html) ?: return ""
        val raw = match.groupValues[1]
        return try { JSONObject("{\"value\":\"$raw\"}").optString("value") } catch (_: Exception) { raw }
    }

    private fun isUiLabel(text: String): Boolean = text.trim().lowercase(Locale.ROOT) in setOf(
        "like", "liked", "unlike", "reply", "comments", "comment", "post", "send",
        "нравится", "не нравится", "ответить", "комментарии", "комментарий", "опубликовать", "отправить",
    )

    private fun looksLikeTime(text: String): Boolean = text.trim().matches(Regex("\\d+[smhdw]|[0-9]{1,2}[.-][0-9]{1,2}([.-][0-9]{2,4})?"))

    private fun looksLikeUsername(text: String): Boolean = text.trim().removePrefix("@").matches(Regex("[A-Za-z0-9._-]{2,40}"))

    private fun isReplyNode(node: UiNode): Boolean {
        val label = node.textOrDescription().trim().lowercase(Locale.ROOT)
        return node.visible && (label == "reply" || label == "ответить" ||
            label.startsWith("reply to ") || label.startsWith("ответить ") ||
            node.resourceId.contains("reply", true))
    }

    private fun isLikeNode(node: UiNode): Boolean {
        val label = node.label().lowercase(Locale.ROOT)
        val visual = node.clickable || node.className.contains("button", true) ||
            node.className.contains("image", true) || node.resourceId.contains("like", true)
        return node.visible && visual && (label.contains("like") || label.contains("нрав"))
    }

    private fun isPhotoEvidence(node: UiNode): Boolean {
        if (!node.visible) return false
        val label = node.label().lowercase(Locale.ROOT)
        if (label.contains("avatar") || label.contains("profile") || label.contains("аватар")) return false
        val visual = node.className.contains("image", true) || node.resourceId.contains("image", true) ||
            node.resourceId.contains("photo", true)
        val explicitCommentImage = node.resourceId.contains("comment", true) &&
            (node.resourceId.contains("image", true) || node.resourceId.contains("photo", true))
        return visual && (explicitCommentImage || label.contains("photo") || label.contains("picture") ||
            label.contains("media") || label.contains("attachment") || label.contains("фото") || label.contains("изображ"))
    }
}
