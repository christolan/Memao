package com.xiaojiwei.memao.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted values leave Android Keystore. This file is excluded from backup. */
class TokenVault(context: Context, preferencesName: String = "memao_credentials") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val alias = "memao_tokens_v1"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    @Synchronized
    fun put(accountId: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        check(preferences.edit().putString(accountId, encoded).commit()) { "无法保存登录令牌" }
    }

    @Synchronized
    fun get(accountId: String): String? {
        val value = preferences.getString(accountId, null) ?: return null
        return runCatching {
            val pieces = value.split(':', limit = 2)
            require(pieces.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun remove(accountId: String) {
        check(preferences.edit().remove(accountId).commit()) { "无法清理登录令牌" }
    }

    /** Finish an interrupted account removal without ever exposing plaintext values. */
    @Synchronized
    fun retainAccounts(accountIds: Set<String>) {
        val orphaned = preferences.all.keys - accountIds
        if (orphaned.isNotEmpty()) {
            val edit = preferences.edit()
            orphaned.forEach(edit::remove)
            check(edit.commit()) { "无法完成账号令牌清理" }
        }
    }
}
