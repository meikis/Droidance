// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

class AirAdbKey private constructor(
    private val privateKey: RSAPrivateCrtKey,
    private val publicKey: RSAPublicKey,
    val publicKeyBytes: ByteArray
) {
    val sslContext: SSLContext by lazy { createSslContext() }

    fun signToken(token: ByteArray): ByteArray {
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val payload = digestInfo + token
        val block = ByteArray(KEY_LENGTH_BYTES)
        block[0] = 0
        block[1] = 1
        val separator = KEY_LENGTH_BYTES - payload.size - 1
        Arrays.fill(block, 2, separator, 0xff.toByte())
        block[separator] = 0
        System.arraycopy(payload, 0, block, KEY_LENGTH_BYTES - payload.size, payload.size)

        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, privateKey)
            doFinal(block)
        }
    }

    private fun createSslContext(): SSLContext {
        val certificate = createSelfSignedCertificate(privateKey, publicKey)
        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "adb"

            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? {
                return keyTypes?.firstOrNull { it == "RSA" }?.let { alias }
            }

            override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? {
                return keyTypes?.firstOrNull { it == "RSA" }?.let { alias }
            }

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == this.alias) privateKey else null
        }
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), null)
        }
    }

    companion object {
        private const val KEY_LENGTH_BITS = 2048
        private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
        private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4
        private const val PUBLIC_KEY_STRUCT_SIZE = 524
        private val base64MimeEncoder = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.UTF_8))

        fun loadOrCreate(privateKeyFile: File, publicKeyFile: File): AirAdbKey {
            if (!privateKeyFile.exists()) {
                generate(privateKeyFile, publicKeyFile)
            }
            val privateKey = readPrivateKey(privateKeyFile)
            val publicKey = derivePublicKey(privateKey)
            val publicFileBytes = adbPublicKeyFileBytes(publicKey)
            if (!publicKeyFile.exists() || !publicKeyFile.readBytes().contentEquals(publicFileBytes)) {
                publicKeyFile.parentFile?.mkdirs()
                publicKeyFile.writeBytes(publicFileBytes)
            }
            return AirAdbKey(privateKey, publicKey, publicFileBytes + 0.toByte())
        }

        private fun generate(privateKeyFile: File, publicKeyFile: File) {
            val keyPair = KeyPairGenerator.getInstance("RSA").run {
                initialize(KEY_LENGTH_BITS)
                generateKeyPair()
            }
            privateKeyFile.parentFile?.mkdirs()
            publicKeyFile.parentFile?.mkdirs()
            writePrivateKey(privateKeyFile, keyPair)
            val publicKey = keyPair.public as RSAPublicKey
            publicKeyFile.writeBytes(adbPublicKeyFileBytes(publicKey))
        }

        private fun writePrivateKey(file: File, keyPair: KeyPair) {
            val encoded = base64MimeEncoder.encodeToString(keyPair.private.encoded)
            file.writeText("-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----", Charsets.UTF_8)
        }

        private fun readPrivateKey(file: File): RSAPrivateCrtKey {
            val encoded = file.readText(Charsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .filterNot { it.isWhitespace() }
            val key = KeyFactory.getInstance("RSA").generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
            )
            return key as? RSAPrivateCrtKey
                ?: error("AIR ADB private key is not an RSA CRT key")
        }

        private fun derivePublicKey(privateKey: RSAPrivateCrtKey): RSAPublicKey {
            val spec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
        }

        private fun adbPublicKeyFileBytes(publicKey: RSAPublicKey): ByteArray {
            val encoded = Base64.getEncoder().encodeToString(convertRsaPublicKeyToAdbFormat(publicKey))
            return "$encoded unknown@unknown".toByteArray(Charsets.UTF_8)
        }

        private fun convertRsaPublicKeyToAdbFormat(publicKey: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ZERO.setBit(32)
            val modulus = publicKey.modulus
            val n0Inv = modulus.remainder(r32).modInverse(r32).negate()
            val rr = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
                .modPow(BigInteger.valueOf(2), modulus)

            return ByteBuffer.allocate(PUBLIC_KEY_STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN).run {
                putInt(KEY_LENGTH_WORDS)
                putInt(n0Inv.toInt())
                toAdbWords(modulus).forEach { putInt(it) }
                toAdbWords(rr).forEach { putInt(it) }
                putInt(publicKey.publicExponent.toInt())
                array()
            }
        }

        private fun toAdbWords(value: BigInteger): IntArray {
            val words = IntArray(KEY_LENGTH_WORDS)
            val r32 = BigInteger.ZERO.setBit(32)
            var current = value
            for (index in words.indices) {
                val parts = current.divideAndRemainder(r32)
                words[index] = parts[1].toInt()
                current = parts[0]
            }
            return words
        }

        private fun createSelfSignedCertificate(
            privateKey: RSAPrivateCrtKey,
            publicKey: RSAPublicKey
        ): X509Certificate {
            val algorithmIdentifier = derSequence(
                derOid("1.2.840.113549.1.1.11"),
                derNull()
            )
            val name = derSequence(
                derSet(
                    derSequence(
                        derOid("2.5.4.3"),
                        derUtf8String("Droidance AIR")
                    )
                )
            )
            val validity = derSequence(
                derGeneralizedTime("20200101000000Z"),
                derGeneralizedTime("20491231235959Z")
            )
            val tbsCertificate = derSequence(
                derInteger(BigInteger.ONE),
                algorithmIdentifier,
                name,
                validity,
                name,
                publicKey.encoded
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(tbsCertificate)
                sign()
            }
            val certificate = derSequence(
                tbsCertificate,
                algorithmIdentifier,
                derBitString(signature)
            )
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        }

        private fun derSequence(vararg values: ByteArray): ByteArray = derTagged(0x30, values.concat())
        private fun derSet(vararg values: ByteArray): ByteArray = derTagged(0x31, values.concat())
        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
        private fun derUtf8String(value: String): ByteArray = derTagged(0x0c, value.toByteArray(Charsets.UTF_8))
        private fun derGeneralizedTime(value: String): ByteArray = derTagged(0x18, value.toByteArray(Charsets.US_ASCII))
        private fun derBitString(value: ByteArray): ByteArray = derTagged(0x03, byteArrayOf(0) + value)

        private fun derInteger(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return derTagged(0x02, bytes)
        }

        private fun derOid(value: String): ByteArray {
            val parts = value.split(".").map { it.toLong() }
            val body = ByteArrayOutputStream()
            body.write((parts[0] * 40 + parts[1]).toInt())
            parts.drop(2).forEach { part ->
                val stack = ArrayDeque<Byte>()
                var current = part
                stack.addFirst((current and 0x7f).toByte())
                current = current shr 7
                while (current > 0) {
                    stack.addFirst(((current and 0x7f) or 0x80).toByte())
                    current = current shr 7
                }
                stack.forEach { body.write(it.toInt()) }
            }
            return derTagged(0x06, body.toByteArray())
        }

        private fun derTagged(tag: Int, body: ByteArray): ByteArray {
            return byteArrayOf(tag.toByte()) + derLength(body.size) + body
        }

        private fun derLength(length: Int): ByteArray {
            if (length < 128) return byteArrayOf(length.toByte())
            var current = length
            val bytes = ArrayDeque<Byte>()
            while (current > 0) {
                bytes.addFirst((current and 0xff).toByte())
                current = current shr 8
            }
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        private fun Array<out ByteArray>.concat(): ByteArray {
            val out = ByteArrayOutputStream()
            forEach { out.write(it) }
            return out.toByteArray()
        }
    }
}
