package com.xiaojiwei.memao.data.local

import androidx.room.AutoMigration
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

@Entity(tableName = "sync_operations", indices = [Index(value = ["accountId", "memoName"])])
data class SyncOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val memoName: String,
    val kind: String,
    val base: String?,
    val proposed: String,
    val draft: String? = null,
    val status: String = "QUEUED",
    val error: String? = null,
    val remote: String? = null,
    val submitted: String? = null,
    val started: Boolean = false,
)

@Entity(tableName = "sync_progress")
data class SyncProgress(
    @PrimaryKey val accountId: String,
    val completedAt: Long,
    val reconciledAt: Long,
    val cursor: String?,
    val error: String?,
)

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
    @Query("DELETE FROM memos WHERE NOT EXISTS (SELECT 1 FROM sync_operations WHERE sync_operations.accountId = memos.accountId AND sync_operations.memoName = memos.name)") suspend fun clearMemos()

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

    @Query("SELECT * FROM sync_operations WHERE accountId = :accountId ORDER BY id") fun observeSync(accountId: String): Flow<List<SyncOperation>>
    @Query("SELECT * FROM sync_operations WHERE accountId = :accountId ORDER BY id") suspend fun syncOperations(accountId: String): List<SyncOperation>
    @Query("SELECT * FROM sync_operations WHERE accountId = :accountId AND memoName = :name ORDER BY id") suspend fun syncOperations(accountId: String, name: String): List<SyncOperation>
    @Query("SELECT * FROM sync_operations ORDER BY id") suspend fun allSyncOperations(): List<SyncOperation>
    @Query("SELECT * FROM sync_operations WHERE id = :id") suspend fun syncOperation(id: Long): SyncOperation?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSync(operation: SyncOperation): Long
    @Query("UPDATE sync_operations SET status = 'QUEUED' WHERE status = 'SENDING'") suspend fun resetInterruptedSync()
    @Query("DELETE FROM sync_operations WHERE id = :id") suspend fun deleteSync(id: Long)
    @Query("DELETE FROM sync_operations WHERE accountId = :id") suspend fun deleteSyncOperations(id: String)
    @Query("SELECT * FROM sync_progress WHERE accountId = :id") fun observeSyncProgress(id: String): Flow<SyncProgress?>
    @Query("SELECT * FROM sync_progress WHERE accountId = :id") suspend fun syncProgress(id: String): SyncProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSyncProgress(progress: SyncProgress)
    @Query("DELETE FROM sync_progress WHERE accountId = :id") suspend fun deleteSyncProgress(id: String)

}

@Database(
    entities = [AccountRecord::class, MemoRecord::class, DraftRecord::class, BlobRecord::class, AttemptRecord::class, SyncOperation::class, SyncProgress::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MemaoDatabase : RoomDatabase() {
    abstract fun dao(): MemaoDao
}
