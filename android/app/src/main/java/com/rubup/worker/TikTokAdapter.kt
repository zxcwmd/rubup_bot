package com.rubup.worker

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.ZoneOffset

fun canonicalVideoUrl(value: String): String? {
    val uri = try { Uri.parse(value) } catch (_: Exception) { return null }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase().orEmpty()
    if (host !in setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com")) return null
    val match = Regex("^/(@[^/]+)/video/(\\d+)").find(uri.path.orEmpty()) ?: return null
    return "https://www.tiktok.com/${match.groupValues[1]}/video/${match.groupValues[2]}"
}

fun videoIdFromUrl(value: String): String? = canonicalVideoUrl(value)
    ?.let { Regex("/video/(\\d+)").find(it)?.groupValues?.get(1) }

class TikTokAdapter(private val runtime: WebViewRuntime, private val settings: SettingsStore) {
    private val itemSelectors = listOf(
        "[data-e2e='comment-item']", "[data-e2e*='comment-item' i]",
        "div[class*='CommentItem']", "div[class*='comment-item']",
    )
    private val authorSelectors = listOf("[data-e2e='comment-username']", "[data-e2e*='comment-username' i]", "a[href^='/@']")
    private val textSelectors = listOf("[data-e2e='comment-text']", "[data-e2e*='comment-text' i]", "p", "span")
    private val replySelectors = listOf(
        "[data-e2e='comment-reply']", "[data-e2e*='reply' i]", "button", "span",
    )
    private val inputSelectors = listOf(
        "[data-e2e='comment-reply-input'][contenteditable='true']",
        "div[contenteditable='true'][data-e2e*='reply' i]",
        "[data-e2e='comment-input'][contenteditable='true']",
        "div[contenteditable='true'][data-e2e*='comment' i]",
        "div[contenteditable='true']", "textarea",
    )
    private val imageInputSelectors = listOf("input[type='file'][accept*='image' i]", "input[type='file']")
    private val imagePreviewSelectors = listOf(
        "[data-e2e*='comment-image' i] img", "[class*='Comment'] [class*='Image'] img", "img[alt*='comment' i]",
    )
    private val publishedImageSelectors = listOf(
        "[data-e2e*='comment-image' i] img", "[class*='CommentImage' i] img",
        "[class*='ImageContainer' i] img", "img[alt*='comment' i]",
    )
    private val submitSelectors = listOf(
        "[data-e2e='comment-post']", "button[data-e2e*='comment-post' i]",
        "button", "[role='button'][aria-label*='Post' i]", "[role='button'][aria-label*='Опубликовать' i]",
    )
    private val likeSelectors = listOf(
        "[data-e2e='comment-like-button']", "button[data-e2e*='comment-like' i]",
        "[role='button'][data-e2e*='comment-like' i]", "button[aria-label*='like' i]",
        "[role='button'][aria-label*='like' i]", "[data-e2e='comment-like-icon']",
    )
    private val likedSelectors = listOf(
        "[data-e2e='comment-liked-icon']", "[data-e2e*='comment-unlike' i]",
        "[aria-label*='unlike' i]", "[aria-label*='Не нравится' i]", "[class*='like' i][class*='liked' i]",
    )

    private fun helpers(): String = """
        const itemSelectors=${JSONArray(itemSelectors)};
        const authorSelectors=${JSONArray(authorSelectors)};
        const textSelectors=${JSONArray(textSelectors)};
        const replySelectors=${JSONArray(replySelectors)};
        const inputSelectors=${JSONArray(inputSelectors)};
        const imageInputSelectors=${JSONArray(imageInputSelectors)};
        const imagePreviewSelectors=${JSONArray(imagePreviewSelectors)};
        const imageSelectors=${JSONArray(publishedImageSelectors)};
        const submitSelectors=${JSONArray(submitSelectors)};
        const likeSelectors=${JSONArray(likeSelectors)};
        const likedSelectors=${JSONArray(likedSelectors)};
        const visible=(e)=>!!e && e.getClientRects().length>0;
        const all=(root,selectors)=>Array.from(new Set(selectors.flatMap(s=>Array.from(root.querySelectorAll(s)))));
        const firstVisible=(root,selectors)=>all(root,selectors).find(visible)||null;
        const firstExisting=(root,selectors)=>all(root,selectors)[0]||null;
        const items=()=>all(document,itemSelectors);
        const pick=(root,selectors)=>firstExisting(root,selectors);
        const hasText=(el,selectors)=>selectors.some(s=>{const f=el.querySelector(s);return !!(f&&(f.textContent||'').trim());});
        const textOf=(el,selectors)=>{for(const s of selectors){const f=el.querySelector(s);if(f&&(f.textContent||'').trim())return f.textContent.trim();}return '';};
        const isReply=(el)=>{const t=(el.textContent||'').trim().toLowerCase();return t==='reply'||t==='ответить'||el.matches("[data-e2e*='reply' i]");};
        const isSubmit=(el)=>{const t=(el.textContent||'').trim().toLowerCase();const a=(el.getAttribute('aria-label')||'').toLowerCase();return el.matches("[data-e2e*='comment-post' i]")||t==='post'||t==='опубликовать'||a.includes('post')||a.includes('опубликовать');};
    """.trimIndent()

    private fun value(script: String): Any? {
        val raw = runtime.evaluate(script)
        return JSONTokener(raw).nextValue()
    }

    private fun string(script: String): String = value(script)?.toString().orEmpty()

    private fun bool(script: String): Boolean = value(script) == true

    private fun currentUrl(): String = string("location.href")

    private fun checkChallenge() {
        val challenged = bool(
            "!!document.querySelector(\"iframe[src*='captcha' i],#captcha_container,[data-testid*='captcha' i]\")",
        )
        if (challenged) throw ChallengeRequired("TikTok показал капчу. Откройте приложение и пройдите её в WebView.")
    }

    fun ensureAuthenticated() {
        if (!currentUrl().contains("tiktok.com")) runtime.navigate("https://www.tiktok.com/")
        checkChallenge()
        val loggedIn = bool("!!document.querySelector(\"[data-e2e='profile-icon'],a[href^='/@'][data-e2e*='profile' i]\")")
        if (!loggedIn) throw SessionRequired("Нет сохранённого входа в TikTok. Откройте RubUP Worker и войдите в WebView.")
    }

    fun discover(tag: String, limit: Int): List<String> {
        val clean = tag.removePrefix("#")
        runtime.navigate("https://www.tiktok.com/tag/${Uri.encode(clean)}")
        val found = linkedMapOf<String, String>()
        var stagnant = 0
        repeat(7) {
            val array = value("Array.from(document.querySelectorAll(\"a[href*='/video/']\")).map(a=>a.href)") as? JSONArray
            val before = found.size
            if (array != null) {
                for (index in 0 until array.length()) {
                    val canonical = canonicalVideoUrl(array.optString(index)) ?: continue
                    val id = videoIdFromUrl(canonical) ?: continue
                    found[id] = canonical
                    if (found.size >= limit) break
                }
            }
            if (found.size >= limit || stagnant >= 2) return found.values.take(limit)
            stagnant = if (found.size == before) stagnant + 1 else 0
            bool("window.scrollBy(0,2200);true")
            Thread.sleep(1200)
            checkChallenge()
        }
        return found.values.take(limit)
    }

    fun getVideo(url: String, sourceTag: String = ""): Video {
        var canonical = canonicalVideoUrl(url)
        if (canonical == null) {
            val host = Uri.parse(url).host.orEmpty()
            if (!host.endsWith("tiktok.com")) throw BotFailure("Разрешены только ссылки TikTok")
            runtime.navigate(url)
            canonical = canonicalVideoUrl(currentUrl()) ?: throw BotFailure("Не удалось разрешить ссылку TikTok")
        }
        if (canonicalVideoUrl(currentUrl()) != canonical) runtime.navigate(canonical)
        checkChallenge()
        val videoId = videoIdFromUrl(canonical)!!
        val metadata = value(
            """(()=>{const target=${JSONObject.quote(videoId)};let best=null;
            const walk=(v,d)=>{if(!v||d>16||typeof v!=='object')return;
            const id=String(v.id??v.aweme_id??v.awemeId??'');
            if(id===target&&('createTime' in v||'create_time' in v||'desc' in v||'author' in v)){
            if(!best||Object.keys(v).length>Object.keys(best).length)best=v;return;}
            for(const k of Object.keys(v))walk(v[k],d+1);};
            for(const s of document.scripts){if(!s.textContent)continue;try{walk(JSON.parse(s.textContent),0);}catch(e){}}
            let desc=best?(best.desc||best.description||''):'';let author=best?best.author:'';
            if(!desc){const m=document.querySelector("meta[property='og:description'],meta[name='description']");desc=m?m.content:'';}
            if(author&&typeof author==='object')author=author.uniqueId||author.unique_id||author.nickname||'';
            return {desc:String(desc||''),author:String(author||''),createTime:best?(best.createTime||best.create_time||null):null};})()""",
        ) as JSONObject
        var published = metadata.optString("createTime").toLongOrNull()
        if (published != null && published > 10_000_000_000L) published /= 1000
        if (published == null) {
            val html = string("document.documentElement.outerHTML")
            val match = Regex("[\"\\\\]createTime[\"\\\\]\\s*:\\s*[\"\\\\]?(\\d{10,13})").find(html)
            published = match?.groupValues?.get(1)?.toLongOrNull()
            if (published != null && published > 10_000_000_000L) published /= 1000
        }
        var author = metadata.optString("author")
        if (author.isBlank()) author = Uri.parse(canonical).pathSegments.firstOrNull().orEmpty().removePrefix("@")
        val description = metadata.optString("desc").trim()
        val tags = Regex("#([\\p{L}\\p{N}_]+)").findAll(description).map { it.groupValues[1].lowercase() }.toMutableSet()
        if (sourceTag.isNotBlank()) tags += sourceTag.removePrefix("#").lowercase()
        return Video(videoId, canonical, author, description, published, tags)
    }

    fun getComments(videoUrl: String, limit: Int = 100): List<TikTokComment> {
        val canonical = canonicalVideoUrl(videoUrl) ?: throw BotFailure("Нужна каноническая ссылка на видео")
        if (canonicalVideoUrl(currentUrl()) != canonical) runtime.navigate(canonical)
        repeat(2) {
            bool("window.scrollBy(0,1800);true")
            Thread.sleep(700)
        }
        val script = """(()=>{${helpers()};return items().slice(0,$limit).map((element,domIndex)=>{
            const authorNode=pick(element,authorSelectors);let author=authorNode?(authorNode.textContent||'').trim():'';
            if(!author&&authorNode&&authorNode.href){const m=authorNode.href.match(/\/@([^/?#]+)/);author=m?m[1]:'';}
            let externalId=element.getAttribute('data-comment-id')||element.getAttribute('data-cid')||element.getAttribute('data-id')||'';
            return {externalId,author:author.replace(/^@/,''),text:textOf(element,textSelectors),
            hasImage:imageSelectors.some(s=>!!element.querySelector(s)),domIndex};}).filter(x=>x.text);})()"""
        val array = value(script) as? JSONArray ?: return emptyList()
        val videoId = videoIdFromUrl(canonical)!!
        return buildList {
            for (index in 0 until array.length()) {
                val raw = array.getJSONObject(index)
                val author = raw.optString("author")
                val text = raw.optString("text")
                val id = raw.optString("externalId").ifBlank { stableCommentId(videoId, author, text) }
                val comment = TikTokComment(
                    id = id, videoId = videoId, author = author, text = text,
                    hasImage = raw.optBoolean("hasImage"), domIndex = raw.optInt("domIndex"),
                    isOwn = settings.tiktokUsername.isNotBlank() && author.lowercase().removePrefix("@") == settings.tiktokUsername,
                )
                if (none { it.id == id }) add(comment)
            }
        }
    }

    fun postComment(action: ActionItem, image: ImageAsset?, imageUri: android.net.Uri?, stopCheck: () -> Unit) {
        val canonical = canonicalVideoUrl(action.videoUrl) ?: throw BotFailure("Неканоническая ссылка")
        if (canonicalVideoUrl(currentUrl()) != canonical) runtime.navigate(canonical)
        checkChallenge()
        if (action.actionType == ActionType.REPLY) {
            stopCheck()
            val found = bool(
                """(()=>{${helpers()};const text=${JSONObject.quote(action.targetText)};const author=${JSONObject.quote(action.targetAuthor.lowercase())};
                const item=items().find(e=>(e.innerText||'').includes(text)&&(!author||(e.innerText||'').toLowerCase().includes(author)));
                if(!item)return false;const button=all(item,replySelectors).filter(visible).find(isReply);
                if(!button)return false;button.click();return true;})()""",
            )
            if (!found) throw ManualActionRequired("Не найден комментарий или кнопка ответа")
        }
        if (action.actionType == ActionType.COMMENT) {
            if (image == null || imageUri == null) throw ManualActionRequired("Для основного комментария обязательна новая картинка")
            stopCheck()
            runtime.prepareImage(imageUri)
            val clicked = bool("(()=>{${helpers()};const input=firstExisting(document,imageInputSelectors);if(!input)return false;input.click();return true;})()")
            if (!clicked) throw ManualActionRequired("В WebView не найдена загрузка фото")
            runtime.awaitImageDelivered()
            Thread.sleep(1400)
            val preview = bool("(()=>{${helpers()};return !!firstVisible(document,imagePreviewSelectors);})()")
            if (!preview) throw ManualActionRequired("Фото не подтверждено предпросмотром; публикация не отправлена")
        }
        val filled = bool(
            """(()=>{${helpers()};const input=firstVisible(document,inputSelectors);if(!input)return false;input.focus();
            const text=${JSONObject.quote(action.text)};if(input.isContentEditable)input.textContent=text;else input.value=text;
            input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));return true;})()""",
        )
        if (!filled) throw ManualActionRequired("Поле комментария не найдено")
        Thread.sleep(500)
        stopCheck()
        try {
            val clicked = bool(
                """(()=>{${helpers()};const button=all(document,submitSelectors).filter(visible).find(isSubmit);
                if(!button||button.disabled||button.getAttribute('aria-disabled')==='true')return false;button.click();return true;})()""",
            )
            if (!clicked) throw ManualActionRequired("Кнопка публикации не найдена или неактивна")
        } catch (error: ManualActionRequired) {
            throw error
        } catch (error: Exception) {
            throw ManualActionRequired("Нажатие публикации не подтверждено: ${error.message}", true)
        }
        // After the click every uncertainty means manual review, never an automatic retry.
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
                    "Нажатие выполнено, но комментарий с обязательным фото не подтверждён. Проверьте вручную перед повтором.",
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

    fun likeOwnComment(action: ActionItem, stopCheck: () -> Unit): Boolean {
        if (settings.tiktokUsername.isBlank()) return false
        val canonical = canonicalVideoUrl(action.videoUrl) ?: return false
        val expected = normalizeText(action.text)
        var target = getComments(canonical, 120).filter {
            normalizeText(it.text) == expected && it.isOwn
        }.lastOrNull() ?: return false
        if (likeState(target.domIndex)) return true
        stopCheck()
        val clicked = try {
            bool(
                """(()=>{${helpers()};const item=items()[${target.domIndex}];if(!item)return false;
                const button=all(item,likeSelectors).filter(visible)[0];if(!button)return false;button.click();return true;})()""",
            )
        } catch (_: Exception) {
            // The click may have reached TikTok; a second click could remove the like.
            return false
        }
        if (!clicked) return false
        return try {
            Thread.sleep(700)
            if (likeState(target.domIndex)) {
                true
            } else {
                runtime.navigate(canonical)
                target = getComments(canonical, 120).filter {
                    normalizeText(it.text) == expected && it.isOwn
                }.lastOrNull() ?: return false
                likeState(target.domIndex)
            }
        } catch (_: Exception) {
            // Uncertain post-click state is manual work, not a safe automatic retry.
            false
        }
    }

    private fun likeState(domIndex: Int): Boolean = bool(
        """(()=>{${helpers()};const item=items()[$domIndex];if(!item)return false;
        const button=all(item,likeSelectors)[0];for(const e of [button,button&&button.parentElement]){
        if(e&&(e.getAttribute('aria-pressed')==='true'||/unlike|не нравится/i.test(e.getAttribute('aria-label')||'')))return true;}
        return likedSelectors.some(s=>!!item.querySelector(s));})()""",
    )
}

fun videoDateAllowed(video: Video, allowedTags: Set<String> = Defaults.TAGS.toSet()): Pair<Boolean, String> {
    if (video.publishedAt == null) return false to "Дата публикации не определена"
    val minimum = LocalDate.parse(Defaults.MIN_VIDEO_DATE).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    if (video.publishedAt < minimum) return false to "Опубликовано раньше ${Defaults.MIN_VIDEO_DATE}"
    if (video.matchedTags.intersect(allowedTags).isEmpty()) return false to "Нет подходящего тега"
    return true to ""
}
