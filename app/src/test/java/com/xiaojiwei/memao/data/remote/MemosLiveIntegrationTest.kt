package com.xiaojiwei.memao.data.remote

import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.CreateMemo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.UpdateMemo
import com.xiaojiwei.memao.model.UploadFile
import com.xiaojiwei.memao.model.newUid
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** Opt-in: only an isolated loopback 0.31.0 instance, never a production server. */
class MemosLiveIntegrationTest {
    @Test fun isolatedServerAudioOnlyAndManagementRoundTrip() = runBlocking {
        val credentialPath = System.getenv("MEMAO_TEST_CREDENTIALS")
        assumeTrue("Set MEMAO_TEST_CREDENTIALS for isolated server checks", !credentialPath.isNullOrBlank())
        val credentials = JSONObject(File(credentialPath!!).readText())
        val origin = MemosProtocol.serverOrigin(credentials.getString("serverUrl"))
        require(origin.host == "127.0.0.1" || origin.host == "localhost") {
            "Integration tests only permit an isolated loopback server"
        }
        val token = credentials.getString("token")
        val gateway = OkHttpMemosGateway({ token })
        val info = gateway.inspect(origin.toString(), token)
        assertEquals("0.31.0", info.version)
        val account = Account("integration-only", origin.toString(), info.userName, info.displayName)
        val directory = Files.createTempDirectory("memao-live-network").toFile()
        val uid = newUid()
        val memoName = "memos/$uid"
        var created = false
        var attachmentName: String? = null
        try {
            val original = File(directory, "sample.wav").apply { writeBytes(waveSample()) }
            val uploadRequest = UploadFile(original, "sample.wav", "audio/wav", newUid())
            val attachment = gateway.upload(account, uploadRequest)
            attachmentName = attachment.name
            assertEquals("attachments/${uploadRequest.uid}", attachment.name)
            // Retry uses the stable UID and resolves the existing completed upload.
            assertEquals(attachment.name, gateway.upload(account, uploadRequest).name)
            val memo = gateway.create(account, CreateMemo(uid, "", listOf(attachment)))
            created = true
            assertEquals("", memo.content)
            assertEquals("PRIVATE", memo.visibility)
            assertEquals(attachment.name, memo.attachments.single().name)
            try {
                gateway.create(account, CreateMemo(uid, "", listOf(attachment)))
                fail("Stable memo UID must not silently create a second memo")
            } catch (error: MemosException) {
                // Resource binding is validated before the memo UID unique constraint.
                // An already-linked attachment can therefore cause FAILED_PRECONDITION
                // (HTTP 400) before duplicate memo creation reaches ALREADY_EXISTS.
                if (error.statusCode == 400) {
                    assertTrue(error.message.orEmpty().contains("already bound to another memo"))
                } else assertEquals(409, error.statusCode)
            }
            val resolvedDuplicate = gateway.get(account, memoName)
            assertEquals(memoName, resolvedDuplicate.name)
            assertEquals("", resolvedDuplicate.content)
            assertEquals("PRIVATE", resolvedDuplicate.visibility)
            assertEquals(listOf(attachment.name), resolvedDuplicate.attachments.map { it.name })
            // Without an attachment-binding failure, the same UID reaches the
            // database unique check and returns ALREADY_EXISTS; original data stays.
            try {
                gateway.create(account, CreateMemo(uid, "", emptyList()))
                fail("A duplicate UID cannot replace an existing memo")
            } catch (error: MemosException) { assertEquals(409, error.statusCode) }
            assertEquals(listOf(attachment.name), gateway.get(account, memoName).attachments.map { it.name })

            val downloaded = File(directory, "downloaded.wav")
            gateway.download(account, memo.attachments.single(), downloaded)
            assertArrayEquals(original.readBytes(), downloaded.readBytes())

            val tag = "memao-$uid"
            val literal = "Alpha 中文 %_ \"quote\" \\slash"
            val content = "# 测试\n\n**原文** $literal #$tag\n\n```text\nkeep raw\n```"
            val edited = gateway.update(account, UpdateMemo(memoName, content, memo.attachments))
            assertEquals(content, edited.content)
            assertEquals(memo.visibility, edited.visibility)
            assertEquals(memo.createTime, edited.createTime)
            assertEquals(attachment.name, edited.attachments.single().name)
            val filtered = gateway.list(account, MemoQuery(keyword = literal, tag = tag))
            assertTrue(filtered.memos.any { it.name == memoName })
            assertFalse(gateway.list(account, MemoQuery(keyword = "not-$uid", tag = tag)).memos.any { it.name == memoName })
            assertTrue(gateway.tags(account).any { it.tag == tag && it.count >= 1 })

            gateway.update(account, UpdateMemo(memoName, state = "ARCHIVED"))
            assertFalse(gateway.list(account, MemoQuery(keyword = uid)).memos.any { it.name == memoName })
            assertTrue(gateway.list(account, MemoQuery(archived = true)).memos.any { it.name == memoName })
            gateway.update(account, UpdateMemo(memoName, state = "NORMAL"))
            assertFalse(gateway.get(account, memoName).archived)
            gateway.delete(account, memoName)
            created = false
            try { gateway.get(account, memoName); fail("Deleted memo must be absent") }
            catch (error: MemosException) { assertEquals(404, error.statusCode) }
        } finally {
            try {
                if (created) gateway.delete(account, memoName)
                attachmentName?.let { name ->
                    val request = Request.Builder().url(origin.newBuilder().addPathSegments("api/v1/$name").build())
                        .header("Authorization", "Bearer $token").delete().build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        check(response.isSuccessful || response.code == 404) {
                            "Audio fixture cleanup returned HTTP ${response.code}"
                        }
                    }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun isolatedServerImageOnlyPrivateDownload() = runBlocking {
        withLiveFixture { fixture ->
            val original = File(fixture.directory, "image.png")
            val bitmap = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, 0xff336699.toInt())
                setRGB(2, 1, 0xffeeddcc.toInt())
            }
            assertTrue(ImageIO.write(bitmap, "png", original))
            val attachment = fixture.upload(original, "image/png")
            val memo = fixture.create("", listOf(attachment))
            assertEquals("", memo.content)
            assertEquals("PRIVATE", memo.visibility)
            assertTrue(memo.attachments.single().isImage)
            val downloaded = File(fixture.directory, "downloaded.png")
            fixture.gateway.download(fixture.account, memo.attachments.single(), downloaded)
            val decoded = ImageIO.read(downloaded)
            assertNotNull(decoded)
            assertEquals(3, decoded.width)
            assertEquals(2, decoded.height)
            assertEquals(bitmap.getRGB(0, 0), decoded.getRGB(0, 0))
            // A private note's media is not readable by an anonymous requester.
            fixture.http.newCall(Request.Builder()
                .url(fixture.account.serverUrl.trimEnd('/') + "/file/attachments/${attachment.uid}")
                .get().build()).execute().use { anonymous ->
                assertTrue(anonymous.code == 401 || anonymous.code == 403 || anonymous.code == 404)
            }
        }
    }

    @Test fun isolatedServerLargeAudioUsesMultipleChunksAndPreservesBytes() = runBlocking {
        val uploadCalls = AtomicInteger()
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST" && request.url.encodedPath == "/api/v1/attachments:upload") {
                uploadCalls.incrementAndGet()
            }
            chain.proceed(request)
        }.build()
        withLiveFixture(http) { fixture ->
            val original = File(fixture.directory, "large-audio.wav").apply {
                writeBytes(waveSample(3 * 1024 * 1024))
            }
            assertTrue(original.length() > OkHttpMemosGateway.MAX_CHUNK_SIZE)
            val attachment = fixture.upload(original, "audio/wav")
            // One specification request followed by at least two bounded data requests.
            assertTrue(uploadCalls.get() >= 3)
            assertEquals(original.length(), attachment.size)
            val memo = fixture.create("", listOf(attachment))
            val downloaded = File(fixture.directory, "large-downloaded.wav")
            fixture.gateway.download(fixture.account, memo.attachments.single(), downloaded)
            assertEquals(original.length(), downloaded.length())
            assertArrayEquals(sha256(original), sha256(downloaded))
        }
    }

    @Test fun isolatedServerExistingPdfSurvivesContentAndAttachmentUpdates() = runBlocking {
        withLiveFixture { fixture ->
            // A server-existing non-editable attachment fixture; this does not add
            // PDF import to the app UI or widen the product's allowed new media.
            val pdf = File(fixture.directory, "existing.pdf").apply {
                writeText("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")
            }
            val attachment = fixture.upload(pdf, "application/pdf")
            val advanced = "| 原始 | 表格 |\n| --- | --- |\n| A | B |\n\n```text\nkeep advanced source\n```"
            val memo = fixture.create(advanced, listOf(attachment))
            val updated = fixture.gateway.update(fixture.account, UpdateMemo(
                name = memo.name, content = "$advanced\n\n普通修改", attachments = memo.attachments))
            assertEquals("$advanced\n\n普通修改", updated.content)
            assertEquals(listOf(attachment.name), updated.attachments.map { it.name })
            assertEquals("application/pdf", updated.attachments.single().type)
            val fetched = fixture.gateway.get(fixture.account, memo.name)
            assertEquals(updated.content, fetched.content)
            assertEquals(listOf(attachment.name), fetched.attachments.map { it.name })
            val downloaded = File(fixture.directory, "retained.pdf")
            fixture.gateway.download(fixture.account, fetched.attachments.single(), downloaded)
            assertArrayEquals(pdf.readBytes(), downloaded.readBytes())
        }
    }

    @Test fun isolatedServerPaginationReturnsThreeFixtureMemosWithoutDuplicates() = runBlocking {
        withLiveFixture { fixture ->
            val marker = "pagination-${newUid()}"
            val expected = (1..3).map { index -> fixture.create("$marker item $index", emptyList()).name }.toSet()
            val query = MemoQuery(keyword = marker, pageSize = 2)
            val first = fixture.gateway.list(fixture.account, query)
            assertEquals(2, first.memos.size)
            assertTrue(first.nextPageToken.isNotBlank())
            val second = fixture.gateway.list(fixture.account, query, first.nextPageToken)
            assertEquals(1, second.memos.size)
            assertTrue(second.nextPageToken.isEmpty())
            val names = (first.memos + second.memos).map { it.name }
            assertEquals(3, names.distinct().size)
            assertEquals(expected, names.toSet())
            val times = (first.memos + second.memos).map { it.createTime }
            assertEquals(times.sortedDescending(), times)
        }
    }

    @Test fun isolatedServerOwnPublicMemoRetainsVisibilityAfterOrdinaryEdit() = runBlocking {
        withLiveFixture { fixture ->
            val uid = newUid()
            val name = "memos/$uid"
            fixture.memoNames.add(name)
            // Public visibility is seeded through the fixture HTTP API, not through
            // the client create method, which always creates PRIVATE memos.
            val seeded = fixture.requestJson("POST", "/api/v1/memos?memoId=$uid", JSONObject()
                .put("content", "existing public ${newUid()}").put("visibility", "PUBLIC"))
            assertEquals("PUBLIC", seeded.getString("visibility"))
            val before = fixture.gateway.get(fixture.account, name)
            assertEquals("PUBLIC", before.visibility)
            val after = fixture.gateway.update(fixture.account, UpdateMemo(
                name, content = before.content + "\n\n客户端普通编辑", attachments = before.attachments))
            assertEquals("PUBLIC", after.visibility)
            assertEquals(before.createTime, after.createTime)
            assertEquals(fixture.account.userName, after.creator)
            assertEquals("PUBLIC", fixture.gateway.get(fixture.account, name).visibility)
        }
    }

    @Test fun isolatedServerUntaggedFilterCombinesSearchAndPagination() = runBlocking {
        withLiveFixture { fixture ->
            val marker = "untagged-${newUid()}"
            val plain = (1..3).map { fixture.create("$marker Alpha item $it", emptyList()) }
            val namedTag = fixture.create("$marker Alpha #无标签", emptyList())
            val ordinaryTag = fixture.create("$marker Alpha #work", emptyList())
            val archived = fixture.create("$marker Alpha archived", emptyList())
            fixture.gateway.update(fixture.account, UpdateMemo(archived.name, state = "ARCHIVED"))
            val query = MemoQuery(keyword = "$marker alpha", untagged = true, pageSize = 2)
            val first = fixture.gateway.list(fixture.account, query)
            assertEquals(2, first.memos.size)
            assertTrue(first.nextPageToken.isNotEmpty())
            val second = fixture.gateway.list(fixture.account, query, first.nextPageToken)
            assertEquals(1, second.memos.size)
            assertTrue(second.nextPageToken.isEmpty())
            val actual = first.memos + second.memos
            assertEquals(plain.map { it.name }.toSet(), actual.map { it.name }.toSet())
            assertTrue(actual.all { it.tags.isEmpty() && !it.archived })
            assertEquals(5, fixture.gateway.list(fixture.account, MemoQuery(keyword = marker)).memos.size)
            assertEquals(listOf(namedTag.name), fixture.gateway.list(fixture.account,
                MemoQuery(keyword = marker, tag = "无标签")).memos.map { it.name })
            assertEquals(listOf(ordinaryTag.name), fixture.gateway.list(fixture.account,
                MemoQuery(keyword = marker, tag = "work")).memos.map { it.name })
            assertTrue(fixture.gateway.list(fixture.account,
                query.copy(keyword = "$marker absent")).memos.isEmpty())

            // Removing/adding the final tag must immediately change filter membership.
            fixture.gateway.update(fixture.account, UpdateMemo(namedTag.name, content = "$marker Alpha now plain"))
            fixture.gateway.update(fixture.account, UpdateMemo(plain.first().name, content = "$marker Alpha #work"))
            val changed = fixture.gateway.list(fixture.account, query.copy(pageSize = 30)).memos
            assertEquals((plain.drop(1).map { it.name } + namedTag.name).toSet(), changed.map { it.name }.toSet())
        }
    }

    private suspend fun withLiveFixture(
        http: OkHttpClient = OkHttpClient(),
        block: suspend (LiveFixture) -> Unit,
    ) {
        val path = System.getenv("MEMAO_TEST_CREDENTIALS")
        assumeTrue("Set MEMAO_TEST_CREDENTIALS for isolated server checks", !path.isNullOrBlank())
        val credentials = JSONObject(File(path!!).readText())
        val origin = MemosProtocol.serverOrigin(credentials.getString("serverUrl"))
        require(origin.host == "127.0.0.1" || origin.host == "localhost") {
            "Integration tests only permit an isolated loopback server"
        }
        val token = credentials.getString("token")
        val gateway = OkHttpMemosGateway({ token }, http)
        val info = gateway.inspect(origin.toString(), token)
        assertEquals("0.31.0", info.version)
        val fixture = LiveFixture(
            Account("fixture-${newUid()}", origin.toString(), info.userName, info.displayName),
            gateway, http, token, Files.createTempDirectory("memao-live-fixture").toFile(),
        )
        var failure: Throwable? = null
        try { block(fixture) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { fixture.cleanup() }
            catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private class LiveFixture(
        val account: Account,
        val gateway: OkHttpMemosGateway,
        val http: OkHttpClient,
        private val token: String,
        val directory: File,
    ) {
        val memoNames = mutableListOf<String>()
        private val attachmentNames = mutableListOf<String>()

        suspend fun upload(file: File, type: String): Attachment {
            val uid = newUid()
            attachmentNames.add("attachments/$uid")
            return gateway.upload(account, UploadFile(file, file.name, type, uid))
        }

        suspend fun create(content: String, attachments: List<Attachment>): com.xiaojiwei.memao.model.Memo {
            val uid = newUid()
            memoNames.add("memos/$uid")
            return gateway.create(account, CreateMemo(uid, content, attachments))
        }

        fun requestJson(method: String, path: String, body: JSONObject? = null): JSONObject {
            val request = Request.Builder().url(account.serverUrl.trimEnd('/') + path)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
                .build()
            return http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Fixture request returned HTTP ${response.code}" }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

        suspend fun cleanup() {
            val failures = mutableListOf<Throwable>()
            memoNames.distinct().forEach { name ->
                try { gateway.delete(account, name) }
                catch (error: MemosException) { if (error.statusCode != 404) failures.add(error) }
                catch (error: Throwable) { failures.add(error) }
            }
            // Deleting a note also removes its bound media. Explicitly clean any
            // attachment uploaded before a failed create, scoped to this fixture's UIDs.
            attachmentNames.distinct().forEach { name ->
                try {
                    MemosProtocol.resourceName(name, "attachments")
                    val request = Request.Builder()
                        .url(account.serverUrl.trimEnd('/') + "/api/v1/$name")
                        .header("Authorization", "Bearer $token").delete().build()
                    http.newCall(request).execute().use { response ->
                        check(response.isSuccessful || response.code == 404) {
                            "Fixture attachment cleanup returned HTTP ${response.code}"
                        }
                    }
                } catch (error: Throwable) { failures.add(error) }
            }
            directory.deleteRecursively()
            if (failures.isNotEmpty()) throw AssertionError("Isolated fixture cleanup failed").also { error ->
                failures.forEach(error::addSuppressed)
            }
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun waveSample(pcmSize: Int = 160): ByteArray {
        val pcm = ByteArray(pcmSize)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
    }
}
