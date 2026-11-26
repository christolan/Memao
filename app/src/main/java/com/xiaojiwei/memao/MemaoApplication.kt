package com.xiaojiwei.memao

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.data.remote.OkHttpMemosGateway
import com.xiaojiwei.memao.domain.MemaoRepository
import java.util.concurrent.TimeUnit

class MemaoApplication : Application() {
    internal val repository by lazy {
        val vault = TokenVault(this)
        MemaoRepository(this, OkHttpMemosGateway(vault::get), vault, scheduleSync = ::requestSync)
    }

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "memo-periodic-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MemoSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
        requestSync()
    }

    private fun requestSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "memo-write-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MemoSyncWorker>().setConstraints(constraints).build(),
        )
    }
}

class MemoSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MemaoApplication).repository
        val again = repository.synchronize(retry = true)
        return if (again && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
