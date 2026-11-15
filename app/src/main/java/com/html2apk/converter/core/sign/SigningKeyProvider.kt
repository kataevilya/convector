package com.html2apk.converter.core.sign

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Ключ подписи хранится локально в приватной папке приложения (PKCS#12).
 * При первом запуске генерируется RSA-2048 + самоподписанный сертификат на 25 лет,
 * поэтому приложение полностью автономно и не требует встроенного keystore.
 *
 * Один и тот же ключ переиспользуется, значит обновления уже установленных
 * APK (тот же Package ID) будут ставиться поверх без ошибки SIGNATURE.
 */
internal class SigningKeyProvider(context: Context) {

    data class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val alias: String
    )

    private val keyStoreFile = File(File(context.filesDir, "signing").apply { mkdirs() }, KEYSTORE_NAME)

    @Synchronized
    fun load(): SigningMaterial {
        if (keyStoreFile.exists()) {
            runCatching { return readFromKeyStore() }
                .onFailure { keyStoreFile.delete() }
        }
        return createAndStore()
    }

    private fun readFromKeyStore(): SigningMaterial {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        FileInputStream(keyStoreFile).use { keyStore.load(it, PASSWORD) }
        val key = keyStore.getKey(ALIAS, PASSWORD) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        return SigningMaterial(key, certificate, ALIAS)
    }

    private fun createAndStore(): SigningMaterial {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()

        val certificate = SelfSignedCertificate.create(
            keyPair = keyPair,
            commonName = "HTML2APK Local Signing",
            organization = "HTML2APK Converter",
            validDays = 25 * 365
        )

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(certificate))
        FileOutputStream(keyStoreFile).use { keyStore.store(it, PASSWORD) }

        return SigningMaterial(keyPair.private, certificate, ALIAS)
    }

    private companion object {
        const val KEYSTORE_NAME = "html2apk-signing.p12"
        const val KEYSTORE_TYPE = "PKCS12"
        const val ALIAS = "html2apk"
        val PASSWORD = "html2apk-local-key".toCharArray()
    }
}
