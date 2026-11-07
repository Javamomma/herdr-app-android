package herdr.app.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest (this device's SSH key, a saved password), encrypted with
 * an AES key that never leaves the Android Keystore.
 */
class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val sealed = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val sealed = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(c.doFinal(sealed, 12, sealed.size - 12))
        }.getOrNull()
    }

    private companion object { const val ALIAS = "herdr-secrets" }
}

/** This phone's own Ed25519 SSH key: made once, kept encrypted, public half shown to add on the Mac. */
class DeviceKey(private val secrets: Secrets) {
    /** The private key in OpenSSH format (what sshj loads). */
    fun privateKey(): String = secrets.get(PRIVATE) ?: generate().also { secrets.put(PRIVATE, it.first); secrets.put(PUBLIC, it.second) }.first

    /** `ssh-ed25519 AAAA… herdr-android`, for the Mac's ~/.ssh/authorized_keys. */
    fun publicKey(): String {
        privateKey()
        return secrets.get(PUBLIC)!!
    }

    private fun generate(): Pair<String, String> {
        val secret = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = secret.encoded
        val pub = secret.generatePublicKey().encoded
        val comment = "herdr-android"
        val pubBlob = sshBytes { str("ssh-ed25519".toByteArray()); str(pub) }
        val check = SecureRandom().nextInt()
        var privSection = sshBytes {
            writeInt(check); writeInt(check)
            str("ssh-ed25519".toByteArray()); str(pub); str(seed + pub); str(comment.toByteArray())
        }
        var pad = 1
        while (privSection.size % 8 != 0) privSection += byteArrayOf((pad++).toByte())
        val body = sshBytes {
            write("openssh-key-v1".toByteArray()); write(0)
            str("none".toByteArray()); str("none".toByteArray()); str(ByteArray(0))
            writeInt(1); str(pubBlob); str(privSection)
        }
        val b64 = Base64.encodeToString(body, Base64.NO_WRAP).chunked(70).joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
        val public = "ssh-ed25519 ${Base64.encodeToString(pubBlob, Base64.NO_WRAP)} $comment"
        return pem to public
    }

    private companion object {
        const val PRIVATE = "device-key"
        const val PUBLIC = "device-key.pub"
    }
}

private fun sshBytes(f: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).f()
    return out.toByteArray()
}

private fun DataOutputStream.str(b: ByteArray) {
    writeInt(b.size)
    write(b)
}

/** `SHA256:…` as `ssh-keygen -lf` prints it. */
fun fingerprint(blob: ByteArray): String =
    "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_WRAP or Base64.NO_PADDING)
