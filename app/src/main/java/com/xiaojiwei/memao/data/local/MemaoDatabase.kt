package com.xiaojiwei.memao.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountRecord(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "memos", primaryKeys = ["accountId", "name"], indices = [Index("accountId")])
data class MemoRecord(val accountId: String, val name: String, val payload: String, val lastUsed: Long)

@Entity(tableName = "drafts", indices = [Index("accountId")])
data class DraftRecord(@PrimaryKey val id: String, val accountId: String, val revision: Long, val updatedAt: Long, val payload: String)

@Entity(tableName = "blobs", indices = [Index("accountId"), Index(value = ["accountId", "remoteName"])])
data class BlobRecord(
    @PrimaryKey val id: String,
    val accountId: String,
    val remoteName: String?,
    val path: String,
    val size: Long,
    val lastUsed: Long,
)

@Entity(tableName = "attempts", indices = [Index("accountId")])
data class AttemptRecord(@PrimaryKey val draftId: String, val accountId: String, val revision: Long, val payload: String)

@Dao
interface MemaoDao {
    @Query("SELECT * FROM accounts ORDER BY rowid") fun observeAccounts(): Flow<List<AccountRecord>>
    @Query("SELECT * FROM accounts ORDER BY rowid") suspend fun accounts(): List<AccountRecord>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(record: AccountRecord)
    @Query("DELETE FROM accounts WHERE id = :id") suspend fun deleteAccount(id: String)

    @Query("SELECT * FROM memos WHERE accountId = :accountId") fun observeMemos(accountId: String): Flow<List<MemoRecord>>
    @Query("SELECT * FROM memos WHERE accountId = :accountId") suspend fun memos(accountId: String): List<MemoRecord>
    @Query("SELECT * FROM memos") suspend fun allMemos(): List<MemoRecord>
    @Query("SELECT * FROM memos WHERE accountId = :accountId AND name = :name") suspend fun memo(accountId: String, name: String): MemoRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemo(record: MemoRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemos(records: List<MemoRecord>)
    @Query("DELETE FROM memos WHERE accountId = :accountId AND name = :name") suspend fun deleteMemo(accountId: String, name: String)
    @Query("DELETE FROM memos WHERE accountId = :accountId") suspend fun deleteMemos(accountId: String)
    @Query("DELETE FROM memos") suspend fun clearMemos()

    @Query("SELECT * FROM drafts WHERE accountId = :accountId ORDER BY updatedAt DESC") fun observeDrafts(accountId: String): Flow<List<DraftRecord>>
    @Query("SELECT * FROM drafts WHERE accountId = :accountId ORDER BY updatedAt DESC") suspend fun drafts(accountId: String): List<DraftRecord>
    @Query("SELECT * FROM drafts") suspend fun allDrafts(): List<DraftRecord>
    @Query("SELECT * FROM drafts WHERE id = :id AND accountId = :accountId") suspend fun draft(accountId: String, id: String): DraftRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(record: DraftRecord)
    @Query("DELETE FROM drafts WHERE accountId = :accountId AND id = :id") suspend fun deleteDraft(accountId: String, id: String)
    @Query("DELETE FROM drafts WHERE accountId = :accountId") suspend fun deleteDrafts(accountId: String)

    @Query("SELECT * FROM blobs") suspend fun blobs(): List<BlobRecord>
    @Query("SELECT * FROM blobs WHERE accountId = :accountId") suspend fun blobs(accountId: String): List<BlobRecord>
    @Query("SELECT * FROM blobs WHERE accountId = :accountId AND remoteName = :name ORDER BY lastUsed DESC LIMIT 1") suspend fun blob(accountId: String, name: String): BlobRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBlob(record: BlobRecord)
    @Query("DELETE FROM blobs WHERE id = :id") suspend fun deleteBlob(id: String)
    @Query("DELETE FROM blobs WHERE accountId = :accountId") suspend fun deleteBlobs(accountId: String)

    @Query("SELECT * FROM attempts") suspend fun attempts(): List<AttemptRecord>
    @Query("SELECT * FROM attempts WHERE accountId = :accountId AND draftId = :draftId") suspend fun attempt(accountId: String, draftId: String): AttemptRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttempt(record: AttemptRecord)
    @Query("DELETE FROM attempts WHERE accountId = :accountId AND draftId = :draftId") suspend fun deleteAttempt(accountId: String, draftId: String)
    @Query("DELETE FROM attempts WHERE accountId = :accountId") suspend fun deleteAttempts(accountId: String)
}

@Database(entities = [AccountRecord::class, MemoRecord::class, DraftRecord::class, BlobRecord::class, AttemptRecord::class], version = 1, exportSchema = true)
abstract class MemaoDatabase : RoomDatabase() {
    abstract fun dao(): MemaoDao
}
