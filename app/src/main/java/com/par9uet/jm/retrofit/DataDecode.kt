package com.par9uet.jm.retrofit

import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

fun decryptData(str: String): String {
    val decryptKey = ApiContext.getDataDecryptKey()
    val secretKey = SecretKeySpec(decryptKey.toByteArray(Charset.forName("UTF-8")), "AES")

    // 配置 Cipher
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)

    // 解密数据
    val encryptedBytes = android.util.Base64.decode(str, android.util.Base64.DEFAULT)
    val decryptedBytes = cipher.doFinal(encryptedBytes)

    return String(decryptedBytes, Charset.forName("UTF-8"))
}
