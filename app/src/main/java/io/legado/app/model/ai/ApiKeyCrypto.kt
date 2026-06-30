package io.legado.app.model.ai

import cn.hutool.crypto.symmetric.AES
import io.legado.app.constant.AppConst
import io.legado.app.help.crypto.SymmetricCryptoAndroid

object ApiKeyCrypto {

    private val key by lazy { AppConst.androidId.encodeToByteArray(0, 16) }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return SymmetricCryptoAndroid("AES", key).encryptBase64(plaintext)
    }

    fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        return runCatching { AES(key).decryptStr(ciphertext) }.getOrDefault("")
    }
}
