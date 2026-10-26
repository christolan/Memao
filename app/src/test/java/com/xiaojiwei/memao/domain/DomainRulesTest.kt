package com.xiaojiwei.memao.domain

import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import org.junit.Assert.*
import org.junit.Test

class DomainRulesTest {
    @Test fun serverNormalizationKeepsOriginAndRejectsUnsupportedInput() {
        assertEquals("http://example.com", DomainRules.normalizeServer(" HTTP://Example.COM:80/ "))
        assertEquals("https://example.com:8443", DomainRules.normalizeServer("https://EXAMPLE.com:8443/"))
        assertEquals("http://[::1]:5230", DomainRules.normalizeServer("http://[::1]:5230"))
        for (invalid in listOf("example.com", "file:///tmp", "https://a/path", "https://user:pass@a", "https://a?token=x", "https://a#fragment", "http://a:0")) {
            assertTrue(invalid, runCatching { DomainRules.normalizeServer(invalid) }.isFailure)
        }
    }

    @Test fun literalSearchDoesNotSplitWhitespaceOrInterpretWildcards() {
        val memo = Memo("memos/1", "Hello World 100% _value 中文", "users/1", tags = listOf("随想", "Todo"))
        assertTrue(DomainRules.matches(memo, MemoQuery(keyword = "hello WORLD", tag = "随想")))
        assertFalse(DomainRules.matches(memo, MemoQuery(keyword = "hello 中文")))
        assertTrue(DomainRules.matches(memo, MemoQuery(keyword = "100% _")))
        assertFalse(DomainRules.matches(memo, MemoQuery(keyword = "%中文")))
        assertFalse(DomainRules.matches(memo, MemoQuery(tag = "todo")))
        assertFalse(DomainRules.matches(memo, MemoQuery(archived = true)))
    }

    @Test fun offlineCaseFoldingMatchesAsciiServerSemantics() {
        assertEquals("abc 中文 Ää", DomainRules.asciiFold("ABC 中文 Ää"))
    }

    @Test fun conflictsCompareContentAndAttachmentsEvenWithinSameTimestamp() {
        val attachment = Attachment("attachments/1", "原图.png", "image/png", 7)
        val base = Memo("memos/1", "旧内容", "users/1", updateTime = "2026-10-26T00:00:00Z", attachments = listOf(attachment))
        assertFalse(DomainRules.sameBase(base, base.copy(content = "新内容")))
        assertFalse(DomainRules.sameBase(base, base.copy(attachments = emptyList())))
        assertFalse(DomainRules.sameBase(base, base.copy(visibility = "PUBLIC")))
        assertFalse(DomainRules.sameBase(base, base.copy(state = "ARCHIVED")))
        assertTrue(DomainRules.sameBase(base, base.copy(updateTime = "2026-10-26T00:00:01Z")))
    }

    @Test fun ownPersonalPredicateRejectsOtherUsersAndSpaces() {
        val own = Memo("memos/1", "", "users/1")
        assertTrue(DomainRules.isOwnPersonal(own, "users/1"))
        assertFalse(DomainRules.isOwnPersonal(own.copy(creator = "users/2"), "users/1"))
        assertFalse(DomainRules.isOwnPersonal(own.copy(space = "spaces/1"), "users/1"))
    }

    @Test fun removingReferencedResourcesRequiresSourceEditButDoesNotRewriteIt() {
        val resource = Attachment("attachments/abc", "原图.png", "image/png", 7)
        val source = "文字 ![图](/file/attachments/abc/a.png)"
        val memo = Memo("memos/1", source, "users/1", attachments = listOf(resource))
        assertTrue(runCatching { DomainRules.validateAttachmentRemoval(memo, source, emptySet()) }.isFailure)
        DomainRules.validateAttachmentRemoval(memo, source, setOf(resource.name))
        DomainRules.validateAttachmentRemoval(memo, "文字", emptySet())
        // A different UID with the same prefix is not a reference to this attachment.
        DomainRules.validateAttachmentRemoval(memo, "![图](/file/attachments/abc-more/a.png)", emptySet())
        assertEquals(source, memo.content)
    }

    @Test fun resourceRewriteDoesNotChangeAnotherUidWithTheSamePrefix() {
        val original = Attachment("attachments/abc", "a.png", "image/png", externalLink = "https://cdn.example/a.png")
        val replacement = Attachment("attachments/new", "新图.png", "image/png")
        val content = "![一](/file/attachments/abc/a.png) ![二](/file/attachments/abc-more/b.png) [外链](https://cdn.example/a.png) [另一个](https://cdn.example/a.png-more)"
        val rewritten = DomainRules.rewriteAttachmentReference(content, original, replacement)
        assertTrue(rewritten.contains("/file/attachments/new/a.png"))
        assertTrue(rewritten.contains("/file/attachments/abc-more/b.png"))
        assertTrue(rewritten.contains("/file/attachments/new/%E6%96%B0%E5%9B%BE.png"))
        assertTrue(rewritten.contains("https://cdn.example/a.png-more"))
    }
}
