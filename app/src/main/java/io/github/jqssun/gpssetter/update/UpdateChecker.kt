package io.github.jqssun.gpssetter.update

import android.content.Context
import android.os.Parcelable
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {


    fun getLatestRelease() = callbackFlow {
        // Builds without an update repo never contact GitHub.
        if (BuildConfig.UPDATE_REPO.isEmpty()) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        withContext(Dispatchers.IO){
            getReleaseList()?.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName

                // Despite its name, the "update_disabled" preference is the "check for updates"
                // switch (kept from upstream so existing settings stay valid).
                // Offer only a NEWER release: the old "tag differs" test also offered a
                // downgrade whenever the installed build was newer than the latest release.
                if (currentTag != null && PrefManager.isUpdateDisabled &&
                    isNewer(currentTag, BuildConfig.TAG_NAME)) {
                    //New update available!
                    val asset =
                        gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                    val releaseUrl =
                        asset?.browserDownloadUrl?.replace("/download/", "/tag/")?.let {
                            it.substring(0, it.lastIndexOf("/"))
                        }
                    val name = gitHubReleaseResponse.name ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val body = gitHubReleaseResponse.body ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            plainText(body),
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: "https://github.com/${BuildConfig.UPDATE_REPO}/releases",
                            asset?.name ?: "Tears_GPS.apk",
                            releaseUrl ?: "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
                        )
                    ).isSuccess
                } else {
                    this@callbackFlow.trySend(null).isSuccess
                }
            } ?: run {
                this@callbackFlow.trySend(null).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? {

        runCatching {
            apiResponse.getReleases().execute().body()
        }.onSuccess {
            return it
        }.onFailure {
            return null
        }
        return null
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable

    companion object {
        /** "v1.0.10" vs "1.0.2": numbers compared one by one; suffixes like "-foss" ignored. */
        fun isNewer(remoteTag: String, localVersion: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val r = parts(remoteTag)
            val l = parts(localVersion)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        /** The dialog shows plain text: drop the Markdown marks of the GitHub release notes. */
        fun plainText(md: String): String = md
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")       // [text](url) -> text
            .replace(Regex("""(?m)^#{1,6}\s*"""), "")                // ## Heading -> Heading
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")             // **bold** -> bold
            .replace(Regex("""(?m)^(\s*)[-*]\s+"""), "$1• ")         // - item -> • item
            .replace("`", "")
            .trim()
    }
}
