package com.xiaojiwei.memao.model

import java.io.File

/** All calls run off the UI thread; implementations must not log credentials. */
interface MemosGateway {
    suspend fun inspect(serverUrl: String, token: String): ConnectionInfo
    suspend fun list(account: Account, query: MemoQuery, pageToken: String = ""): MemoPage
    suspend fun get(account: Account, name: String): Memo
    suspend fun tags(account: Account): List<TagCount>
    suspend fun create(account: Account, request: CreateMemo): Memo
    suspend fun update(account: Account, request: UpdateMemo): Memo
    suspend fun delete(account: Account, name: String)
    suspend fun upload(account: Account, request: UploadFile): Attachment
    /** Atomically persist complete bytes at destination; no Token on external origins. */
    suspend fun download(account: Account, attachment: Attachment, destination: File)
}
