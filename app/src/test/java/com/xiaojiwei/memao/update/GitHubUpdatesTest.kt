package com.xiaojiwei.memao.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUpdatesTest {
    @Test fun selectsHighestVersionCodeAcrossPublishedVersionedReleases() {
        val releases = JSONArray()
            .put(release().put("tag_name", "v0.2.0").put("body", "versionCode: 19"))
            .put(release().put("tag_name", "v0.3.0").put("body", "versionCode: 22").put("draft", true))
            .put(release().put("tag_name", "sideload-latest").put("body", "versionCode: 23"))
            .put(release().put("tag_name", "v0.8.0").put("body", "Some release notes"))
            .put(release())
        val candidate = checkNotNull(newestRelease(releases, 18))
        assertEquals(
            AppRelease("0.1.19", 20, payload.size.toLong(), digest, fileName),
            readAppUpdate(candidate, manifest()),
        )
        assertNull(newestRelease(releases, 20))
        assertNull(newestRelease(releases, 21))
    }

    @Test fun rejectsIncompletePublicationAndManifestFromDifferentVersion() {
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release().put("tag_name", "v0.1.20")), manifest())
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release().put("body", "versionCode: 21")), manifest())
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release(asset(fileName).put("state", "starter"))), manifest())
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release().put("assets", JSONArray())), manifest())
        }
    }

    @Test fun rejectsMetadataFromDifferentPublishRunsAndUnsafeFileNames() {
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release(asset(fileName).put("size", payload.size + 1))), manifest())
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(release(asset(fileName).put("digest", "sha256:${"0".repeat(64)}"))), manifest())
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(), manifest().put("fileName", "../$fileName"))
        }
        assertThrows(InvalidUpdate::class.java) {
            readAppUpdate(candidate(), manifest().put("versionCode", "20"))
        }
    }

    @Test fun constructsVersionSpecificAssetUrls() {
        val candidate = candidate()
        val base = "https://github.com/christolan/Memao/releases/download/v0.1.19"
        assertEquals("$base/Memao-0.1.19-update.json", downloadUrl(candidate.versionName, candidate.manifestName))
        assertEquals("$base/$fileName", downloadUrl(candidate.versionName, fileName))
    }

    @Test fun copiesVerifiedBytesWithProgress() = runTest {
        val update = readAppUpdate(candidate(), manifest())
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        copyVerifiedApk(update, ByteArrayInputStream(payload), output, progress::add)
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(listOf(54, 100), progress)
    }

    @Test fun rejectsTruncatedOversizedOrModifiedDownloads() = runTest {
        val update = readAppUpdate(candidate(), manifest())
        for (invalidPayload in listOf(payload.copyOf(payload.size - 1), payload + 1.toByte(), ByteArray(payload.size))) {
            val result = runCatching {
                copyVerifiedApk(update, ByteArrayInputStream(invalidPayload), ByteArrayOutputStream())
            }
            assertEquals(InvalidUpdate::class.java, result.exceptionOrNull()?.javaClass)
        }
    }

    private val payload = ByteArray(120_000) { it.toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).toHexString()
    private val fileName = "Memao-0.1.19-release.apk"

    private fun candidate(release: JSONObject = release()) =
        checkNotNull(newestRelease(JSONArray().put(release), 19))

    private fun manifest() = JSONObject()
        .put("versionName", "0.1.19")
        .put("versionCode", 20)
        .put("fileName", fileName)
        .put("size", payload.size)
        .put("sha256", digest)

    private fun release(apk: JSONObject = asset(fileName)) = JSONObject()
        .put("tag_name", "v0.1.19")
        .put("body", "Signed sideload build.\r\n\r\nversionCode: 20\r\n")
        .put("draft", false)
        .put("prerelease", true)
        .put("assets", JSONArray().put(asset("Memao-0.1.19-update.json")).put(apk))

    private fun asset(name: String) = JSONObject()
        .put("name", name)
        .put("state", "uploaded")
        .put("size", if (name == fileName) payload.size else 200)
        .put("digest", "sha256:$digest")
}
