package com.html2apk.converter.core.sign

import com.android.apksig.ApkSigner
import java.io.File

/**
 * Обёртка над com.android.tools.build:apksig.
 *
 * Работает офлайн и на телефоне: apksig - чистый Java-код, который не требует
 * Android SDK, build-tools или zipalign. Подпись v1 (JAR) + v2 (APK Signature Scheme v2)
 * достаточна для установки на любую версию Android (24 … 15+).
 */
internal object ApkSignerWrapper {

    fun sign(
        input: File,
        output: File,
        material: SigningKeyProvider.SigningMaterial,
        minSdk: Int
    ) {
        require(input.isFile) { "Входной APK не найден: ${input.absolutePath}" }
        if (output.exists()) output.delete()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            material.alias,
            material.privateKey,
            listOf(material.certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setMinSdkVersion(minSdk)
            .setOtherSignersSignaturesPreserved(false)
            .setCreatedBy("HTML2APK Converter")
            .build()
            .sign()

        require(output.isFile && output.length() > 0) { "Подпись APK не удалась" }
    }
}
