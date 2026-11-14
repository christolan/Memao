package com.xiaojiwei.memao.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

private const val REPOSITORY = "christolan/Memao"
private const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases"
private const val PAGE_SIZE = 100
private const val MANIFEST_MAX_BYTES = 4096L
private val downloadBase = "https://github.com/$REPOSITORY/releases/download/".toHttpUrl()
private val versionPattern = Regex("[0-9A-Za-z][0-9A-Za-z._+\\-]*")
private val versionCodeLine = Regex("""(?m)^versionCode: ([0-9]+)\r?$""")
private val sha256Pattern = Regex("[a-f0-9]{64}", RegexOption.IGNORE_CASE)

internal data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val sha256: String,
    val fileName: String,
)

internal data class ReleaseCandidate(
    val versionName: String,
    val versionCode: Long,
    val release: JSONObject,
) {
    val manifestName get() = "Memao-$versionName-update.json"
}

internal object GitHubUpdates {
    // The updater never uses the Memos connection, HTTP cleartext, or account credentials.
    private val http = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(installedVersion: Long): AppRelease? {
        // The list includes prereleases. Publication order can differ from Android version order.
        var page = 1
        var newest: ReleaseCandidate? = null
        do {
            val releases = JSONArray(readMetadata(
                "$RELEASES?per_page=$PAGE_SIZE&page=$page",
                "application/vnd.github+json",
                2 * 1024 * 1024,
            ))
            newest = newestRelease(releases, newest?.versionCode ?: installedVersion) ?: newest
            page++
        } while (releases.length() == PAGE_SIZE)

        val candidate = newest ?: return null
        val asset = requirePublishedAsset(candidate.release, candidate.manifestName)
        if (positiveLong(asset, "size") > MANIFEST_MAX_BYTES) throw InvalidUpdate("Manifest asset is too large")
        val manifest = JSONObject(readMetadata(
            downloadUrl(candidate.versionName, candidate.manifestName),
            "application/json",
            MANIFEST_MAX_BYTES,
        ))
        return readAppUpdate(candidate, manifest)
    }

    suspend fun download(release: AppRelease, file: File, onProgress: (Int) -> Unit) {
        request(downloadUrl(release.versionName, release.fileName), "application/octet-stream") { body ->
            val length = body.contentLength()
            if (length >= 0 && length != release.size) throw InvalidUpdate("APK content length differs")
            file.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    copyVerifiedApk(release, input, output, onProgress)
                }
            }
        }
    }

    private suspend fun readMetadata(url: String, accept: String, limit: Long): String =
        request(url, accept) { body ->
            val source = body.source()
            if (source.request(limit + 1)) throw InvalidUpdate("Update metadata exceeds its size limit")
            source.readUtf8()
        }

    private suspend fun <T> request(
        url: String,
        accept: String,
        read: suspend (ResponseBody) -> T,
    ): T = coroutineScope {
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Memao-Updater")
            .build()
        val call = http.newCall(request)
        // Cancel a blocked response-body read as well as an in-flight connection.
        val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
                    read(response.body)
                }
            }
        } finally {
            cancelCall.cancel()
        }
    }
}

internal fun newestRelease(releases: JSONArray, installedVersion: Long): ReleaseCandidate? =
    (0 until releases.length()).asSequence()
        .mapNotNull(releases::optJSONObject)
        .filterNot { it.optBoolean("draft", true) }
        .mapNotNull { release ->
            val tag = release.optString("tag_name")
            val name = tag.removePrefix("v")
            if (tag == name || !versionPattern.matches(name)) return@mapNotNull null
            val code = versionCodeLine.find(release.optString("body"))?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            if (code <= installedVersion || code !in 1..Int.MAX_VALUE.toLong()) return@mapNotNull null
            ReleaseCandidate(name, code, release)
        }
        .maxByOrNull(ReleaseCandidate::versionCode)

internal fun readAppUpdate(candidate: ReleaseCandidate, manifest: JSONObject): AppRelease {
    val name = manifest.getString("versionName")
    val code = positiveLong(manifest, "versionCode")
    val size = positiveLong(manifest, "size")
    val digest = manifest.getString("sha256")
    val fileName = manifest.getString("fileName")
    if (name != candidate.versionName || code != candidate.versionCode || size > 2_147_483_648L ||
        !sha256Pattern.matches(digest) || fileName != "Memao-$name-release.apk"
    ) {
        throw InvalidUpdate("Invalid update manifest")
    }
    val apk = requirePublishedAsset(candidate.release, fileName)
    val apiDigest = apk.optString("digest")
    if (positiveLong(apk, "size") != size ||
        !apk.isNull("digest") && !apiDigest.equals("sha256:$digest", ignoreCase = true)
    ) {
        throw InvalidUpdate("GitHub APK metadata differs from the update manifest")
    }
    return AppRelease(name, code, size, digest, fileName)
}

private fun requirePublishedAsset(release: JSONObject, fileName: String): JSONObject {
    val assets = release.getJSONArray("assets")
    return (0 until assets.length())
        .mapNotNull(assets::optJSONObject)
        .singleOrNull { asset ->
            asset.optString("name") == fileName && asset.optString("state") == "uploaded"
        } ?: throw InvalidUpdate("Release is missing an uploaded $fileName asset")
}

internal fun downloadUrl(versionName: String, fileName: String): String =
    downloadBase.newBuilder().addPathSegment("v$versionName").addPathSegment(fileName).build().toString()

private fun positiveLong(json: JSONObject, key: String): Long {
    val number = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw InvalidUpdate("$key is not an integer")
    }
    if (number <= 0) throw InvalidUpdate("$key must be positive")
    return number
}

internal suspend fun copyVerifiedApk(
    release: AppRelease,
    input: InputStream,
    output: OutputStream,
    onProgress: (Int) -> Unit = {},
) = coroutineScope {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var percent = 0
    while (true) {
        ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > release.size) throw InvalidUpdate("APK exceeds its advertised size")
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
        val progress = (100 * total / release.size).toInt()
        if (progress != percent) {
            percent = progress
            onProgress(percent)
        }
    }
    if (total != release.size || !digest.digest().toHexString().equals(release.sha256, ignoreCase = true)) {
        throw InvalidUpdate("APK size or SHA-256 differs from the update manifest")
    }
}

internal class InvalidUpdate(message: String) : IOException(message)
