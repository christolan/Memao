package com.xiaojiwei.memao.data.remote

import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemosProtocolTest {
    @Test fun filterEscapesOneLiteralWithoutSplittingKeyword() {
        val keyword = "中文 A_% \"quote\" \\slash\nnext"
        val value = MemosProtocol.filter("users/test-user", MemoQuery(keyword = keyword, tag = "x\"y"))
        assertEquals(
            "creator == \"users/test-user\" && space == null && \"x\\\"y\" in tags && " +
                "content.contains(\"中文 A_% \\\"quote\\\" \\\\slash\\nnext\")",
            value,
        )
        assertEquals(1, Regex("content\\.contains").findAll(value).count())
    }

    @Test fun archiveFilterHasNoKeywordOrTag() {
        assertEquals("creator == \"users/self\" && space == null",
            MemosProtocol.filter("users/self", MemoQuery(archived = true, keyword = "word", tag = "tag")))
    }

    @Test fun rootOriginRejectsPathsAndEmbeddedCredentials() {
        assertEquals("http://localhost:5231/", MemosProtocol.serverOrigin(" http://localhost:5231 ").toString())
        listOf("https://host/memos", "https://u:p@host", "https://host?token=value", "file:///tmp/data").forEach {
            try { MemosProtocol.serverOrigin(it); fail("Expected root-only URL rejection") }
            catch (_: MemosException) { }
        }
    }

    @Test fun rawAdvancedContentAndAttachmentMetadataArePreserved() {
        val raw = JSONObject()
            .put("name", "memos/example").put("creator", "users/self")
            .put("content", "```kotlin\nval n = 1\n```\n<table><tr><td>raw</td></tr></table>")
            .put("visibility", "PROTECTED").put("state", "NORMAL")
            .put("attachments", org.json.JSONArray().put(JSONObject()
                .put("name", "attachments/unknown").put("filename", "document.pdf")
                .put("type", "application/pdf").put("size", "9007199254740993")
                .put("mediaMetadata", JSONObject().put("width", 200).put("height", 100))
                .put("unknownField", JSONObject().put("preserve", true))))
        val memo = MemosProtocol.memo(raw)
        assertEquals(raw.getString("content"), memo.content)
        assertEquals("PROTECTED", memo.visibility)
        assertEquals(9007199254740993L, memo.attachments.single().size)
        assertEquals(200, memo.attachments.single().width)
        assertTrue(JSONObject(memo.attachments.single().rawJson).getJSONObject("unknownField").getBoolean("preserve"))
        assertTrue(JSONObject(memo.rawJson).getJSONArray("attachments").length() == 1)
    }

    @Test fun unsafeResourceNamesCannotBecomeRequestPaths() {
        listOf("memos/a/b", "memos/../users/self", "attachments/%2Ftest", "memos/", "users/me?x=y").forEach {
            try { MemosProtocol.resourceName(it, it.substringBefore('/')); fail("Expected invalid resource rejection") }
            catch (_: MemosException) { }
        }
    }
}
