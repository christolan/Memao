package com.xiaojiwei.memao.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.xiaojiwei.memao.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface UpdateState {
    data object Unchecked : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val release: AppRelease, val error: Int? = null) : UpdateState
    data class Downloading(val release: AppRelease, val percent: Int) : UpdateState
    data class Ready(val release: AppRelease, val message: Int? = null) : UpdateState
    data class PreparingInstall(val release: AppRelease) : UpdateState
    data class Confirm(val release: AppRelease, val intent: Intent) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data object Installed : UpdateState
}

/** Called on the main thread by Settings and the package installer receiver. */
internal class AppUpdate private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val apk = File(context.cacheDir, "memao-update.apk")
    private val packageManager = context.packageManager
    private val installer = packageManager.packageInstaller
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Unchecked)
    val state = mutableState.asStateFlow()

    val installedVersionName: String by lazy { installedPackage().versionName.orEmpty() }

    private var downloadJob: Job? = null
    private var sessionId: Int? = null

    fun check() {
        when (state.value) {
            UpdateState.Unchecked, UpdateState.Current, UpdateState.CheckFailed, is UpdateState.Available -> Unit
            else -> return
        }
        mutableState.value = UpdateState.Checking
        scope.launch {
            try {
                val installedVersion = withContext(Dispatchers.IO) {
                    apk.delete()
                    installedPackage().longVersionCode
                }
                val release = GitHubUpdates.check(installedVersion)
                mutableState.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Update lookup failed", error)
                mutableState.value = UpdateState.CheckFailed
            }
        }
    }

    fun download() {
        val release = (state.value as? UpdateState.Available)?.release ?: return
        mutableState.value = UpdateState.Downloading(release, 0)
        downloadJob = scope.launch {
            try {
                GitHubUpdates.download(release, apk) { percent ->
                    // Leave 100% for the verified, ready-to-install state.
                    mutableState.value = UpdateState.Downloading(release, percent.coerceAtMost(99))
                }
                withContext(Dispatchers.IO) { verifyApk(release) }
                mutableState.value = UpdateState.Ready(release)
            } catch (error: CancellationException) {
                apk.delete()
                mutableState.value = UpdateState.Available(release)
                throw error
            } catch (error: Exception) {
                apk.delete()
                Log.w(LOG_TAG, "Update download or APK verification failed", error)
                val message = if (error is InvalidUpdate) R.string.memao_update_invalid_apk
                else R.string.memao_update_download_failed
                mutableState.value = UpdateState.Available(release, message)
            }
        }
    }

    fun cancelDownload() {
        if (state.value is UpdateState.Downloading) downloadJob?.cancel()
    }

    fun install(requestPermission: () -> Unit) {
        val ready = state.value as? UpdateState.Ready ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            mutableState.value = ready.copy(message = R.string.memao_update_install_permission)
            try {
                requestPermission()
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Could not open install permission settings", error)
                mutableState.value = ready.copy(message = R.string.memao_update_install_failed)
            }
            return
        }
        val release = ready.release
        mutableState.value = UpdateState.PreparingInstall(release)
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { stage(release) }
                sessionId = id
                mutableState.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) {
                    // PackageInstaller adds status and confirmation extras to this explicit callback.
                    val callback = PendingIntent.getBroadcast(
                        context,
                        id,
                        Intent(context, UpdateInstallReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE,
                    )
                    installer.openSession(id).use { it.commit(callback.intentSender) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                sessionId?.let(::abandon)
                sessionId = null
                Log.w(LOG_TAG, "Could not submit APK to PackageInstaller", error)
                mutableState.value = if (error is InvalidUpdate || !apk.isFile) {
                    apk.delete()
                    UpdateState.Available(release, R.string.memao_update_download_again)
                } else {
                    UpdateState.Ready(release, R.string.memao_update_install_failed)
                }
            }
        }
    }

    fun onPermissionResult() {
        val ready = state.value as? UpdateState.Ready ?: return
        if (ready.message == R.string.memao_update_install_permission && packageManager.canRequestPackageInstalls()) {
            mutableState.value = ready.copy(message = null)
        }
    }

    fun confirm(launch: (Intent) -> Unit) {
        val confirm = state.value as? UpdateState.Confirm ?: return
        mutableState.value = UpdateState.Installing(confirm.release)
        try {
            launch(confirm.intent)
        } catch (error: Exception) {
            sessionId?.let(::abandon)
            sessionId = null
            Log.w(LOG_TAG, "Could not open Android install confirmation", error)
            mutableState.value = UpdateState.Ready(confirm.release, R.string.memao_update_install_failed)
        }
    }

    fun onInstallResult(result: Intent) {
        val id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (id != sessionId) {
            // A pending confirmation from an earlier process has no corresponding screen state.
            if (id >= 0 && status == PackageInstaller.STATUS_PENDING_USER_ACTION) abandon(id)
            return
        }
        val release = when (val current = state.value) {
            is UpdateState.Confirm -> current.release
            is UpdateState.Installing -> current.release
            else -> return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                mutableState.value = UpdateState.Confirm(release, confirmation)
                return
            }
            abandon(id)
        }
        sessionId = null
        mutableState.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                apk.delete()
                UpdateState.Installed
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateState.Ready(release, R.string.memao_update_install_cancelled)
            }
            else -> {
                Log.w(LOG_TAG, "PackageInstaller status $status: ${result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                UpdateState.Ready(release, R.string.memao_update_install_failed)
            }
        }
    }

    private fun verifyApk(release: AppRelease) {
        val candidate = packageManager.getPackageArchiveInfo(apk.path, signingFlags)
            ?: throw InvalidUpdate("Downloaded APK cannot be parsed")
        val installed = installedPackage()
        if (candidate.packageName != context.packageName ||
            candidate.versionName != release.versionName ||
            candidate.longVersionCode != release.versionCode ||
            candidate.longVersionCode <= installed.longVersionCode
        ) {
            throw InvalidUpdate("APK package or version differs from the expected update")
        }
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val candidateSigners = candidate.signingInfo?.apkContentsSigners.orEmpty().toSet()
        if (installedSigners.isEmpty() || candidateSigners != installedSigners) {
            throw InvalidUpdate("APK signer differs from the installed app")
        }
    }

    private suspend fun stage(release: AppRelease): Int {
        if (apk.length() != release.size) throw InvalidUpdate("Verified APK is no longer cached")
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.size)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, release.size).use { output ->
                        copyVerifiedApk(release, input, output)
                        session.fsync(output)
                    }
                }
            }
        } catch (error: Exception) {
            abandon(id)
            throw error
        }
        return id
    }

    private fun installedPackage() = packageManager.getPackageInfo(context.packageName, signingFlags)

    private fun abandon(id: Int) {
        runCatching { installer.abandonSession(id) }
            .onFailure { Log.w(LOG_TAG, "Could not abandon install session $id", it) }
    }

    companion object {
        private const val LOG_TAG = "Memao.Update"
        private val signingFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

        private var instance: AppUpdate? = null

        fun get(context: Context): AppUpdate =
            instance ?: AppUpdate(context.applicationContext).also { instance = it }
    }
}

internal class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppUpdate.get(context).onInstallResult(intent)
    }
}
