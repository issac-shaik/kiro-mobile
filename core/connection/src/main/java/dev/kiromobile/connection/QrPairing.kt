package dev.kiromobile.connection

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object PcTls {
    fun configure(connection: HttpURLConnection, fingerprint: String?) {
        if (fingerprint == null) return
        require(connection is HttpsURLConnection && fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid pinned HTTPS connection" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificates are unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("PC certificate is missing")
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) throw CertificateException("PC identity changed. Scan a new QR code on your PC.")
            }
        }
        connection.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
        // The scanned certificate identifies the PC even when its LAN IP changes.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            try {
                val actual = MessageDigest.getInstance("SHA-256").digest(session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
                MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())
            } catch (_: Exception) { false }
        }
    }
}

object QrPairing {
    fun redeem(contents: String): Pairing {
        require(contents.length <= 8192) { "This is not a Kiro Mobile QR code" }
        val qr = JSONObject(contents)
        require(qr.optString("kind") == "kiro-mobile-pair" && qr.optInt("version") == 1) { "Scan the QR code shown by your PC companion" }
        require(qr.getLong("expiresAt") > System.currentTimeMillis()) { "QR code expired. Refresh the pairing page on your PC." }
        val code = qr.getString("code")
        require(code.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid pairing invitation" }
        require(!qr.isNull("certSha256")) { "This QR code uses an unsupported public relay. Scan a new QR code from your PC." }
        val pin = qr.getString("certSha256")
        require(pin.matches(Regex("[a-f0-9]{64}"))) { "Invalid PC identity" }
        val endpoints = qr.getJSONArray("endpoints")
        require(endpoints.length() in 1..8) { "Invalid companion addresses" }
        // Validate every candidate before making any request.
        val origins = (0 until endpoints.length()).map { index ->
            val endpoint = endpoints.getString(index)
            val uri = URI(endpoint)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "QR code must contain an HTTPS companion origin" }
            endpoint
        }
        var lastError: Exception? = null
        for (endpoint in origins) {
            val connection = URL("$endpoint/v1/pair").openConnection() as HttpURLConnection
            try {
                PcTls.configure(connection, pin)
                connection.connectTimeout = 4000; connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
                require(status in 200..299) { response.optString("error", "Cannot pair with this PC") }
                val token = response.getString("token")
                require(token.length >= 32) { "PC returned an invalid pairing key" }
                return Pairing(endpoint, token, pin)
            } catch (e: java.io.IOException) { lastError = e }
            finally { connection.disconnect() }
        }
        throw IllegalStateException(if (qr.optString("network") == "tailscale") "Cannot reach your PC. Connect Tailscale on both devices to the same tailnet and check the PC firewall and Tailscale access rules." else "Cannot reach your PC. Use the same Wi-Fi and allow the companion through the PC firewall.", lastError)
    }
}
