package com.par9uet.jm.utils

import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Applies the certificate scope selected for DoH connections. */
fun OkHttpClient.Builder.applyCertificateTrust(
    includeDeviceCertificates: Boolean,
): OkHttpClient.Builder {
    val trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
    )
    trustManagerFactory.init(certificateKeyStore(includeDeviceCertificates))
    val trustManagers = trustManagerFactory.trustManagers
    val trustManager = trustManagers.first { it is X509TrustManager } as X509TrustManager
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustManagers, java.security.SecureRandom())
    sslSocketFactory(sslContext.socketFactory, trustManager)
    return this
}

private fun certificateKeyStore(includeDeviceCertificates: Boolean): KeyStore {
    val androidCaStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
    return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        val aliases = androidCaStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (includeDeviceCertificates || alias.startsWith("system:")) {
                androidCaStore.getCertificate(alias)?.let { certificate ->
                    setCertificateEntry(alias, certificate)
                }
            }
        }
    }
}
