package com.par9uet.jm.retrofit

import com.par9uet.jm.utils.md5

// Network recommendation response decryption key.
const val APP_DATA_SECRET = "185Hcomic3PAPP7R"

/**
 * 管理 API 请求的上下文信息。
 *
 * Retrofit network recommendation requests use a fixed process timestamp (API_TS).
 * 由于 OkHttp 拦截器链和 Retrofit 转换器在同一线程上同步执行，
 * ThreadLocal 可以安全地在拦截器和转换器之间传递每请求时间戳。
 */
object ApiContext {
    private val perRequestTimestamp = ThreadLocal<Long>()

    fun setTimestamp(ts: Long) {
        perRequestTimestamp.set(ts)
    }

    fun getTimestamp(): Long {
        return perRequestTimestamp.get() ?: API_TS
    }

    /** 获取网络推荐响应的数据解密密钥。 */
    fun getDataDecryptKey(): String {
        return md5("${getTimestamp()}$APP_DATA_SECRET")
    }
}
