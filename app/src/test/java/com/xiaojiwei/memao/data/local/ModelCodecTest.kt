package com.xiaojiwei.memao.data.local

import com.xiaojiwei.memao.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelCodecTest {
    @Test fun draftRoundTripPreservesOriginalContentUnknownAttachmentsAndUploadIdentity() {
        val source = "# 标题\n\n```kotlin\nval x = 1\n```\n\n|a|b|\n|-|-|\n|1|2|\n<tag raw=\"yes\">\n\$x\$\n![图片](/file/attachments/image/a.png)"
        val pdf = Attachment("attachments/pdf", "资料.pdf", "application/pdf", 14, rawJson = "{\"futureField\":\"retained\"}")
        val base = Memo("memos/base", source, "users/1", visibility = "PROTECTED", attachments = listOf(pdf), rawJson = "{\"futureMemo\":true}")
        val draft = Draft(accountId = "a", memoName = base.name, content = source + "\n追加", baseMemo = base,
            attachments = listOf(DraftAttachment(filename = pdf.filename, type = pdf.type, size = 14, localPath = "/private/资料.pdf", original = pdf, uploaded = pdf)),
            revision = 12, cursorStart = 3, cursorEnd = 7, sendState = SendState.UNKNOWN, error = "结果未知")
        assertEquals(draft, ModelCodec.draft(ModelCodec.draft(draft)))
        assertEquals("PROTECTED", ModelCodec.draft(ModelCodec.draft(draft)).baseMemo!!.visibility)
    }

    @Test fun nullableNewDraftFieldsAndEmptyBodyAreStable() {
        val draft = Draft(accountId = "a", content = "", attachments = listOf(DraftAttachment(filename = "语音.wav", type = "audio/wav", size = 9, localPath = "/a.wav")))
        val decoded = ModelCodec.draft(ModelCodec.draft(draft))
        assertNull(decoded.memoName)
        assertNull(decoded.baseMemo)
        assertNull(decoded.attachments.single().original)
        assertFalse(decoded.empty)
        assertEquals(draft.createUid, decoded.createUid)
        assertEquals(draft.attachments.single().uploadUid, decoded.attachments.single().uploadUid)
    }

    @Test fun accountAndMemoRoundTripDoNotInventSpacesOrLoseUnicode() {
        val account = Account("id", "http://127.0.0.1:5230", "users/1", "我", version = "0.31.0", needsAuthentication = true)
        assertEquals(account, ModelCodec.account(ModelCodec.account(account)))
        val memo = Memo("memos/1", "纯文字 😀\n#标签", account.userName, visibility = "PUBLIC", tags = listOf("标签"))
        assertEquals(memo, ModelCodec.memo(ModelCodec.memo(memo)))
    }
}
