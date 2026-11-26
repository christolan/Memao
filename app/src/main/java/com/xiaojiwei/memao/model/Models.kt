package com.xiaojiwei.memao.model

import java.io.File
import java.util.UUID

fun newUid(): String = UUID.randomUUID().toString().replace("-", "")

data class ConnectionInfo(
    val userName: String,
    val displayName: String,
    val username: String = "",
    val version: String,
    val commit: String = "",
)

data class Account(
    val id: String,
    val serverUrl: String,
    val userName: String,
    val displayName: String,
    val username: String = "",
    val version: String = "0.31.0",
    val commit: String = "",
    val needsAuthentication: Boolean = false,
)

data class Attachment(
    val name: String,
    val filename: String,
    val type: String,
    val size: Long = 0,
    val externalLink: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val rawJson: String = "",
) {
    val uid: String get() = name.substringAfterLast('/')
    val isImage: Boolean get() = type.startsWith("image/")
    val isAudio: Boolean get() = type.startsWith("audio/")
}

data class Memo(
    val name: String,
    val content: String,
    val creator: String,
    val createTime: String = "",
    val updateTime: String = "",
    val visibility: String = "PRIVATE",
    val state: String = "NORMAL",
    val space: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val tags: List<String> = emptyList(),
    val rawJson: String = "",
) {
    val uid: String get() = name.substringAfterLast('/')
    val archived: Boolean get() = state == "ARCHIVED"
}

data class MemoQuery(
    val archived: Boolean = false,
    val keyword: String = "",
    val tag: String? = null,
    val pageSize: Int = 30,
    val untagged: Boolean = false,
    val updatedSince: String? = null,
)

data class MemoPage(val memos: List<Memo>, val nextPageToken: String = "")
data class TagCount(val tag: String, val count: Int)

data class DraftAttachment(
    val id: String = newUid(),
    val filename: String,
    val type: String,
    val size: Long,
    val localPath: String,
    val original: Attachment? = null,
    val uploadUid: String = newUid(),
    val uploaded: Attachment? = null,
) {
    val isImage: Boolean get() = type.startsWith("image/")
    val isAudio: Boolean get() = type.startsWith("audio/")
}

enum class SendState { SAVED, CHECKING, UPLOADING, SUBMITTING, VERIFYING, FAILED, UNKNOWN, CONFLICT }

data class Draft(
    val id: String = newUid(),
    val accountId: String,
    val memoName: String? = null,
    val createUid: String = newUid(),
    val content: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val baseMemo: Memo? = null,
    val revision: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val cursorStart: Int = 0,
    val cursorEnd: Int = 0,
    val sendState: SendState = SendState.SAVED,
    val error: String? = null,
) {
    val isNew: Boolean get() = memoName == null
    val empty: Boolean get() = content.isBlank() && attachments.isEmpty()

    /** Cursor, preview, timestamps and save metadata do not change a note's contents. */
    fun hasSameContentAs(other: Draft): Boolean = memoName == other.memoName &&
        content == other.content && attachments == other.attachments
}

enum class ConflictChoice { KEEP_SERVER, OVERWRITE, SAVE_AS_NEW }

sealed interface SendResult {
    data class Sent(val memo: Memo, val newerDraftKept: Boolean = false) : SendResult
    data class Conflict(val server: Memo?, val draft: Draft) : SendResult
    data class KeptServer(val server: Memo?, val draft: Draft) : SendResult
    data class Failed(val message: String) : SendResult
    data class Unknown(val message: String) : SendResult
}

data class PageResult(val hasMore: Boolean, val offline: Boolean = false)
data class CacheReport(val ordinaryBytes: Long, val protectedBytes: Long, val removedBytes: Long = 0)
enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM)

/** An immutable file is uploaded verbatim, with a stable ID across retries. */
data class UploadFile(val file: File, val filename: String, val type: String, val uid: String)
data class CreateMemo(val uid: String, val content: String, val attachments: List<Attachment>)
data class UpdateMemo(
    val name: String,
    val content: String? = null,
    val attachments: List<Attachment>? = null,
    val state: String? = null,
)

class MemosException(
    message: String,
    val statusCode: Int = 0,
    val uncertain: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
