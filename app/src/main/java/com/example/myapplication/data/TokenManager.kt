package com.example.myapplication.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

private const val KEYSTORE_ALIAS = "myapp_auth_token_key"
private const val KEY_SIZE = 256
private const val GCM_IV_SIZE = 12 // 96 bits
private const val GCM_TAG_LEN = 128 // bits

class TokenManager(private val context: Context) {

    private val dataStore = context.dataStore
    private val secretKey: SecretKey by lazy { getOrCreateSecretKey() }

    private val tokenKey = stringPreferencesKey("auth_token")

    /**
     * 加密并保存 token 到 DataStore
     */
    suspend fun saveToken(token: String) {
        val encrypted = encrypt(token)
        dataStore.edit { prefs ->
            prefs[tokenKey] = encrypted
        }
    }

    /**
     * 从 DataStore 读取并解密 token
     */
    fun getToken(): Flow<String?> {
        return dataStore.data.map { prefs ->
            val encrypted = prefs[tokenKey] ?: return@map null
            try {
                decrypt(encrypted)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 清除存储的 token
     */
    suspend fun clearToken() {
        dataStore.edit { prefs ->
            prefs.remove(tokenKey)
        }
    }

    /**
     * 是否有 token 存在
     */
    fun hasToken(): Flow<Boolean> {
        return getToken().map { it != null }
    }

    // -----------------------------------------------------------------------
    //  AES/GCM 加解密
    // -----------------------------------------------------------------------

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // GCM 自动生成 12 字节 IV
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // 将 IV 和密文拼在一起，整体 Base64 编码
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size < GCM_IV_SIZE) throw IllegalArgumentException("Invalid ciphertext")

        val iv = combined.copyOfRange(0, GCM_IV_SIZE)
        val ciphertext = combined.copyOfRange(GCM_IV_SIZE, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LEN, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    // -----------------------------------------------------------------------
    //  Android Keystore 密钥管理
    // -----------------------------------------------------------------------

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        // 如果密钥已存在，直接返回
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // 否则生成新的 AES 密钥
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build()
        )
        return keyGenerator.generateKey()
    }
}