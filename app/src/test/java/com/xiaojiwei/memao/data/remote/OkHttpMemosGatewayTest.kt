package com.xiaojiwei.memao.data.remote

import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.UpdateMemo
import com.xiaojiwei.memao.model.CreateMemo
import com.xiaojiwei.memao.model.UploadFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.Base64

class OkHttpMemosGatewayTest {
    @Test fun listUsesPersonalLiteralFilterAndOpaquePageToken() = runBlocking {
        ScriptedServer { Reply(body = """{"memos":[],"nextPageToken":"opaque+/="}""") }.use { server ->
            val gateway = OkHttpMemosGateway({ TOKEN })
            val account = account(server)
            val query = MemoQuery(keyword = "A %_ \"中文\"", tag = "work")
            val page = gateway.list(account, query, "token+/=")
            assertEquals("opaque+/=", page.nextPageToken)
            val received = server.nextRequest()
            val url = (server.origin + received.target).toHttpUrlForTest()
            assertEquals(MemosProtocol.filter(account.userName, query), url.queryParameter("filter"))
            assertEquals("create_time desc", url.queryParameter("orderBy"))
            assertEquals("token+/=", url.queryParameter("pageToken"))
            assertEquals("Bearer $TOKEN", received.headers["authorization"])
        }
    }

    @Test fun updateOnlyTouchesContentAttachmentsAndServerUpdateTime() = runBlocking {
        ScriptedServer { Reply(body = memoJson("PUBLIC")) }.use { server ->
            val result = OkHttpMemosGateway({ TOKEN }).update(account(server), UpdateMemo(
                "memos/example", content = "new", attachments = listOf(
                    Attachment("attachments/keep-pdf", "document.pdf", "application/pdf"))))
            assertEquals("PUBLIC", result.visibility)
            val received = server.nextRequest()
            val url = (server.origin + received.target).toHttpUrlForTest()
            assertEquals("PATCH", received.method)
            assertEquals("content,attachments,update_time", url.queryParameter("updateMask"))
            val body = JSONObject(received.body.toString(Charsets.UTF_8))
            assertEquals(setOf("content", "attachments"), body.keys().asSequence().toSet())
            assertFalse(body.has("visibility"))
            assertFalse(body.has("createTime"))
            assertEquals("attachments/keep-pdf", body.getJSONArray("attachments").getJSONObject(0).getString("name"))
        }
    }

    @Test fun adminReadableOtherUsersAndSpacesAreRejected() = runBlocking {
        listOf(
            """{"name":"memos/other","creator":"users/another","space":"","state":"NORMAL"}""",
            """{"name":"memos/space","creator":"users/self","space":"spaces/team","state":"NORMAL"}""",
        ).forEach { memo ->
            ScriptedServer { Reply(body = """{"memos":[$memo]}""") }.use { server ->
                try {
                    OkHttpMemosGateway({ TOKEN }).list(account(server), MemoQuery())
                    fail("Out-of-scope data must be rejected")
                } catch (error: MemosException) { assertTrue(error.message.orEmpty().contains("范围之外")) }
            }
        }
    }

    @Test fun invalidTokenHasReadableErrorAndNeverEchoesCredential() = runBlocking {
        ScriptedServer { Reply(code = 401, body = """{"message":"bad $TOKEN"}""") }.use { server ->
            try {
                OkHttpMemosGateway({ TOKEN }).get(account(server), "memos/example")
                fail("Expected authentication error")
            } catch (error: MemosException) {
                assertEquals(401, error.statusCode)
                assertFalse(error.uncertain)
                assertFalse(error.message.orEmpty().contains(TOKEN))
            }
        }
    }

    @Test fun malformedTokenIsRejectedBeforeHeaderValidationCanEchoIt() = runBlocking {
        ScriptedServer { Reply(body = "unused") }.use { server ->
            val malformed = "sensitive-token-中文"
            try {
                OkHttpMemosGateway({ malformed }).get(account(server), "memos/example")
                fail("Non-ASCII credentials must be rejected before building a header")
            } catch (error: MemosException) {
                assertEquals(401, error.statusCode)
                assertFalse(error.message.orEmpty().contains(malformed))
                assertFalse(error.message.orEmpty().contains("sensitive-token"))
            }
            assertNull(server.requests.poll(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun apiRedirectIsNotFollowed() = runBlocking {
        ScriptedServer { Reply(body = "secret") }.use { foreign ->
            ScriptedServer { Reply(code = 302, headers = mapOf("Location" to foreign.origin + "/steal")) }.use { server ->
                try {
                    OkHttpMemosGateway({ TOKEN }).get(account(server), "memos/example")
                    fail("Expected redirect rejection")
                } catch (error: MemosException) { assertEquals(302, error.statusCode) }
                assertNull(foreign.requests.poll(150, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun crossOriginAttachmentRedirectDropsTokenAndWritesCompleteFile() = runBlocking {
        val contents = "original-file-bytes".toByteArray()
        ScriptedServer { Reply(bytes = contents) }.use { foreign ->
            ScriptedServer { Reply(code = 302, headers = mapOf("Location" to foreign.origin + "/file")) }.use { server ->
                val directory = Files.createTempDirectory("memao-network-test").toFile()
                try {
                    val destination = File(directory, "audio.bin")
                    OkHttpMemosGateway({ TOKEN }).download(account(server),
                        Attachment("attachments/example", "audio.bin", "audio/wav"), destination)
                    assertArrayEquals(contents, destination.readBytes())
                    assertEquals("Bearer $TOKEN", server.nextRequest().headers["authorization"])
                    assertNull(foreign.nextRequest().headers["authorization"])
                    assertEquals(listOf("audio.bin"), directory.list()?.toList())
                } finally { directory.deleteRecursively() }
            }
        }
    }

    @Test fun externalAttachmentNeverStartsWithToken() = runBlocking {
        ScriptedServer { Reply(body = "external") }.use { foreign ->
            ScriptedServer { Reply(body = "unused") }.use { server ->
                val directory = Files.createTempDirectory("memao-external-test").toFile()
                try {
                    OkHttpMemosGateway({ TOKEN }).download(account(server),
                        Attachment("attachments/example", "external", "application/pdf", externalLink = foreign.origin + "/external"),
                        File(directory, "file"))
                    assertNull(foreign.nextRequest().headers["authorization"])
                    assertNull(server.requests.poll(150, TimeUnit.MILLISECONDS))
                } finally { directory.deleteRecursively() }
            }
        }
    }

    @Test fun failedDownloadLeavesExistingFileUntouched() = runBlocking {
        ScriptedServer { Reply(code = 404, body = "missing") }.use { server ->
            val directory = Files.createTempDirectory("memao-atomic-test").toFile()
            try {
                val file = File(directory, "existing").apply { writeText("keep") }
                try {
                    OkHttpMemosGateway({ TOKEN }).download(account(server),
                        Attachment("attachments/example", "file", "audio/wav"), file)
                    fail("Expected download error")
                } catch (_: MemosException) { }
                assertEquals("keep", file.readText())
                assertEquals(listOf("existing"), directory.list()?.toList())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun writeResponseLostAfterCommitIsUncertainAndNotAutomaticallyRepeated() = runBlocking {
        ScriptedServer { Reply(body = "{", declaredSize = 200) }.use { server ->
            try {
                OkHttpMemosGateway({ TOKEN }).create(account(server), CreateMemo("stable-uid", "draft", emptyList()))
                fail("A truncated successful write response must be uncertain")
            } catch (error: MemosException) { assertTrue(error.uncertain) }
            assertEquals("POST", server.nextRequest().method)
            assertNull(server.requests.poll(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun successfulWriteWithInvalidMemoPayloadRemainsUncertain() = runBlocking {
        ScriptedServer { Reply(body = "{}") }.use { server ->
            try {
                OkHttpMemosGateway({ TOKEN }).create(account(server), CreateMemo("stable-uid", "draft", emptyList()))
                fail("Missing memo identity after a write must be uncertain")
            } catch (error: MemosException) { assertTrue(error.uncertain) }
            server.nextRequest()
            assertNull(server.requests.poll(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun chunkUploadRecoversCommittedOffsetWithoutDuplicatingBytes() = runBlocking {
        val committed = ByteArrayOutputStream()
        var finished = false
        var lostResponse = false
        val metadata = """{"name":"attachments/fixed-file","filename":"test.wav","type":"audio/wav","size":"5"}"""
        ScriptedServer { request ->
            if (request.method == "GET") {
                if (finished) Reply(body = metadata) else Reply(code = 404, body = "{}")
            } else {
                val body = JSONObject(request.body.toString(Charsets.UTF_8))
                if (body.has("spec")) {
                    assertEquals("fixed-file", body.getJSONObject("spec").getString("attachmentId"))
                    assertEquals("5", body.getJSONObject("spec").getString("totalSize"))
                    Reply(body = """{"uploadId":"upload-session","committedSize":"0","maxChunkSize":2}""")
                } else if (!body.has("data")) {
                    Reply(body = """{"uploadId":"upload-session","committedSize":"${committed.size()}","maxChunkSize":2}""")
                } else {
                    assertEquals(committed.size().toLong(), body.getLong("writeOffset"))
                    committed.write(Base64.getDecoder().decode(body.getString("data")))
                    if (!lostResponse) {
                        lostResponse = true
                        Reply(code = 503, body = "{}") // Bytes committed; client must query the offset.
                    } else {
                        finished = body.optBoolean("finishWrite")
                        Reply(body = """{"uploadId":"upload-session","committedSize":"${committed.size()}","maxChunkSize":2${if (finished) ",\"attachment\":$metadata" else ""}}""")
                    }
                }
            }
        }.use { server ->
            val original = Files.createTempFile("memao-upload-test", ".wav").toFile()
            try {
                original.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
                val attachment = OkHttpMemosGateway({ TOKEN }).upload(account(server),
                    UploadFile(original, "test.wav", "audio/wav", "fixed-file"))
                assertEquals("attachments/fixed-file", attachment.name)
                assertArrayEquals(original.readBytes(), committed.toByteArray())
                assertTrue(finished)
                assertTrue(lostResponse)
            } finally { original.delete() }
        }
    }

    private fun account(server: ScriptedServer) = Account("test", server.origin, "users/self", "Self")
    private fun memoJson(visibility: String) = """{"name":"memos/example","content":"new","creator":"users/self","visibility":"$visibility","state":"NORMAL","attachments":[]}"""

    companion object { private const val TOKEN = "test-credential-do-not-log" }
}

private fun String.toHttpUrlForTest(): okhttp3.HttpUrl = toHttpUrl()

internal data class RecordedRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)
internal data class Reply(
    val code: Int = 200,
    val body: String = "",
    val bytes: ByteArray = body.toByteArray(Charsets.UTF_8),
    val headers: Map<String, String> = emptyMap(),
    val declaredSize: Int = bytes.size,
)

/** Small loopback HTTP fixture: tests exercise real URL/header/body transport. */
internal class ScriptedServer(private val respond: (RecordedRequest) -> Reply) : Closeable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${server.localPort}"
    val requests = LinkedBlockingQueue<RecordedRequest>()
    @Volatile private var failure: Throwable? = null
    private val worker = Thread {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = BufferedInputStream(socket.getInputStream())
                    val start = readLine(input).split(' ')
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var read = 0
                    while (read < body.size) {
                        val count = input.read(body, read, body.size - read)
                        if (count < 0) throw java.io.EOFException()
                        read += count
                    }
                    val request = RecordedRequest(start[0], start[1], headers, body)
                    requests.put(request)
                    val reply = respond(request)
                    val output = socket.getOutputStream()
                    val head = buildString {
                        append("HTTP/1.1 ${reply.code} Result\r\n")
                        append("Content-Length: ${reply.declaredSize}\r\nConnection: close\r\n")
                        append("Content-Type: application/json\r\n")
                        reply.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    output.write(head.toByteArray(Charsets.US_ASCII))
                    output.write(reply.bytes)
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            if (error !is SocketException || !server.isClosed) failure = error
        }
    }.apply { isDaemon = true; name = "memao-http-fixture"; start() }

    fun nextRequest(): RecordedRequest = requests.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("No HTTP request received", failure)

    override fun close() { server.close(); worker.join(1000); failure?.let { throw AssertionError("HTTP fixture failed", it) } }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) throw java.io.EOFException()
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
