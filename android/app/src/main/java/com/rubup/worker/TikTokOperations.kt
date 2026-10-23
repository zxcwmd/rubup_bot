package com.rubup.worker

import android.net.Uri

/** Browser/app-specific operations used by the same automation state machine. */
interface TikTokOperations {
    fun ensureAuthenticated()
    fun discover(tag: String, limit: Int): List<String>
    fun getVideo(url: String, sourceTag: String = ""): Video
    fun getComments(videoUrl: String, limit: Int = 100): List<TikTokComment>
    fun postComment(action: ActionItem, image: ImageAsset?, imageUri: Uri?, stopCheck: () -> Unit)
    fun likeOwnComment(action: ActionItem, stopCheck: () -> Unit): Boolean
}
