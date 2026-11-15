package com.html2apk.converter.core.sign

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Генерация самоподписанного сертификата X.509 v3 (SHA256withRSA) прямо на устройстве.
 *
 * apksig (v1/v2) требует именно java.security.cert.X509Certificate, поэтому DER
 * кодируется вручную и парсится штатным CertificateFactory.
 */
internal object SelfSignedCertificate {

    private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val OID_ORGANIZATION = "2.5.4.10"
    private const val OID_COMMON_NAME = "2.5.4.3"

    fun create(
        keyPair: KeyPair,
        commonName: String,
        organization: String,
        validDays: Int
    ): X509Certificate {
        val signatureAlgorithm = DerEncoder.sequence(
            DerEncoder.oid(OID_SHA256_WITH_RSA),
            DerEncoder.nullValue()
        )

        val name = DerEncoder.sequence(
            DerEncoder.set(
                DerEncoder.sequence(
                    DerEncoder.oid(OID_ORGANIZATION),
                    DerEncoder.utf8String(organization)
                )
            ),
            DerEncoder.set(
                DerEncoder.sequence(
                    DerEncoder.oid(OID_COMMON_NAME),
                    DerEncoder.utf8String(commonName)
                )
            )
        )

        val now = System.currentTimeMillis()
        val notBefore = now - 24L * 60L * 60L * 1000L
        val notAfter = now + validDays.toLong() * 24L * 60L * 60L * 1000L
        val serial = BigInteger.valueOf(now / 1000L).abs().max(BigInteger.ONE)

        val tbsCertificate = DerEncoder.sequence(
            // version: [0] EXPLICIT INTEGER 2 (v3)
            DerEncoder.explicit(0, DerEncoder.integer(BigInteger.valueOf(2))),
            DerEncoder.integer(serial),
            signatureAlgorithm,
            name,
            DerEncoder.sequence(
                DerEncoder.utcTime(notBefore),
                DerEncoder.utcTime(notAfter)
            ),
            name,
            keyPair.public.encoded
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        val certificateDer = DerEncoder.sequence(
            tbsCertificate,
            signatureAlgorithm,
            DerEncoder.bitString(signature)
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
    }
}
