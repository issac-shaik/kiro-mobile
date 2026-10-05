package dev.kiromobile.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pairing(val endpoint: String, val token: String)
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("kiro-mobile-pairing",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kiro-mobile-pairing",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(endpoint: String, token: String) {
        val clean=endpoint.trim().trimEnd('/')
        val uri=URI(clean)
        require(uri.scheme=="https" || (uri.scheme=="http" && uri.host in listOf("localhost","127.0.0.1","10.0.2.2"))) { "Use the private HTTPS address from Tailscale Serve" }
        require(!uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty()) { "Enter the companion origin, without a path or credentials" }
        require(token.trim().length>=32) { "Paste the full pairing key from your PC" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encrypted=cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("endpoint",clean).putString("secret",Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).apply()
    }
    fun read(): Pairing? = try {
        val endpoint=prefs.getString("endpoint",null)
        val secret=prefs.getString("secret",null)
        val iv=prefs.getString("iv",null)
        if(endpoint==null||secret==null||iv==null) null else {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP))) }
            Pairing(endpoint,String(cipher.doFinal(Base64.decode(secret,Base64.NO_WRAP)),Charsets.UTF_8))
        }
    } catch(_: Exception) { null }
    fun clear() { prefs.edit().clear().apply() }
}
