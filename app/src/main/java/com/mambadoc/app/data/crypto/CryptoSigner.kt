package com.mambadoc.app.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Dependency-free cryptographic signing using Android KeyStore + java.security.
 *
 * - No BouncyCastle / third-party crypto libraries.
 * - Keys are generated on-device (hardware-backed / StrongBox when available).
 * - Produces detached signatures (file + .sig + .sig.json).
 * - Compiles cleanly in GitHub Actions with standard Android SDK / JDK 17.
 * - Key material is created only at runtime on the user's device.
 */
class CryptoSigner(private val context: Context) {

    data class SignResult(
        val success: Boolean,
        val message: String,
        val signatureFile: File? = null,
        val manifestFile: File? = null,
        val signatureBase64: String? = null
    )

    data class VerifyResult(
        val valid: Boolean,
        val message: String,
        val signedAt: String? = null,
        val alias: String? = null
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "mambadoc_signing_key"
        private const val SIG_ALG = "SHA256withECDSA"
        private const val KEY_SIZE = 256
    }

    fun ensureKey(alias: String = DEFAULT_ALIAS): Boolean {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) return true

            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setKeySize(KEY_SIZE)

            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    builder.setIsStrongBoxBacked(true)
                } catch (_: Exception) {
                    // StrongBox optional
                }
            }

            kpg.initialize(builder.build())
            kpg.generateKeyPair()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun signFile(file: File, alias: String = DEFAULT_ALIAS): SignResult {
        if (!file.exists()) {
            return SignResult(false, "File not found: ${file.absolutePath}")
        }
        if (!ensureKey(alias)) {
            return SignResult(false, "Could not create or load signing key")
        }

        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val privateKey = entry.privateKey

            val sig = Signature.getInstance(SIG_ALG)
            sig.initSign(privateKey)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) {
                    sig.update(buf, 0, n)
                }
            }
            val signatureBytes = sig.sign()
            val b64 = if (Build.VERSION.SDK_INT >= 26) {
                Base64.getEncoder().encodeToString(signatureBytes)
            } else {
                android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP)
            }

            val sigFile = File(file.parentFile, file.name + ".sig")
            sigFile.writeText(b64)

            val whenStr = java.time.Instant.now().toString()
            val manifest = """
                {
                  "file": "${file.name}",
                  "alias": "$alias",
                  "algorithm": "$SIG_ALG",
                  "curve": "secp256r1",
                  "signed_at": "$whenStr",
                  "signature_file": "${sigFile.name}",
                  "app": "MambaDoc"
                }
            """.trimIndent()
            val manifestFile = File(file.parentFile, file.name + ".sig.json")
            manifestFile.writeText(manifest)

            SignResult(
                success = true,
                message = "Signed ${file.name} → ${sigFile.name}",
                signatureFile = sigFile,
                manifestFile = manifestFile,
                signatureBase64 = b64
            )
        } catch (e: Exception) {
            SignResult(false, "Sign failed: ${e.message}")
        }
    }

    fun verifyFile(
        file: File,
        signatureFile: File = File(file.parentFile, file.name + ".sig"),
        alias: String = DEFAULT_ALIAS
    ): VerifyResult {
        if (!file.exists()) return VerifyResult(false, "Data file missing")
        if (!signatureFile.exists()) return VerifyResult(false, "Signature file missing")

        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(alias)) {
                return VerifyResult(false, "Signing key alias not found on this device")
            }
            val cert = ks.getCertificate(alias) as X509Certificate
            val publicKey = cert.publicKey

            val b64 = signatureFile.readText().trim()
            val signatureBytes = if (Build.VERSION.SDK_INT >= 26) {
                Base64.getDecoder().decode(b64)
            } else {
                android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            }

            val sig = Signature.getInstance(SIG_ALG)
            sig.initVerify(publicKey)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) {
                    sig.update(buf, 0, n)
                }
            }
            val ok = sig.verify(signatureBytes)

            val manifestFile = File(file.parentFile, file.name + ".sig.json")
            val signedAt = if (manifestFile.exists()) {
                Regex(""""signed_at"\s*:\s*"([^"]+)"""").find(manifestFile.readText())
                    ?.groupValues?.get(1)
            } else null

            VerifyResult(
                valid = ok,
                message = if (ok) "Signature valid" else "Signature INVALID",
                signedAt = signedAt,
                alias = alias
            )
        } catch (e: Exception) {
            VerifyResult(false, "Verify failed: ${e.message}")
        }
    }

    fun keyFingerprint(alias: String = DEFAULT_ALIAS): String? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(alias)) return null
            val cert = ks.getCertificate(alias) ?: return null
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val dig = md.digest(cert.publicKey.encoded)
            dig.joinToString(":") { "%02X".format(it) }.take(47)
        } catch (_: Exception) {
            null
        }
    }
}
