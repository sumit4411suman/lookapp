package `in`.goxl.mydooraccess

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/** Trusts exactly one certificate, identified by the SHA-256 of its DER encoding. No CA chain is consulted. */
class PinnedTrustManager(pinHex: String) : X509ExtendedTrustManager() {
    private val pin = pinHex.lowercase()

    private fun verify(chain: Array<X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHex()
        if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw CertificateException("Certificate pin mismatch")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) { throw CertificateException("No client auth") }
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) { throw CertificateException("No client auth") }
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) { throw CertificateException("No client auth") }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** HTTPS client for the relay. TLS only, certificate pinned; there is no plaintext or unpinned fallback. */
class HttpsRelayApi(
    private val host: String,
    private val port: Int,
    pinHex: String,
    private val timeoutMs: Int = 4000,
) : RelayApi {
    private val factory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(PinnedTrustManager(pinHex)), null)
    }.socketFactory

    override fun challenge(): String = Wire.parseChallenge(call("GET", "/challenge"))

    override fun unlock(nonce: String, macHex: String): UnlockReply =
        Wire.parseUnlock(call("POST", "/unlock?nonce=" + URLEncoder.encode(nonce, "UTF-8") + "&mac=" + URLEncoder.encode(macHex, "UTF-8")))

    override fun status(): RelayStatus = Wire.parseStatus(call("GET", "/status"))

    private fun call(method: String, path: String): String {
        val c = URL("https://$host:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = factory
        c.hostnameVerifier = HostnameVerifier { _, _ -> true } // identity is the pinned certificate, not a hostname
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = false
        c.requestMethod = method
        try {
            try {
                c.connect()
            } catch (e: SSLException) {
                throw RelayException(FailKind.TLS_FAILED, "TLS failed (wrong certificate pin or not the relay): ${e.message}")
            } catch (e: IOException) {
                throw RelayException(FailKind.OFFLINE, "Cannot reach $host:$port (${e.javaClass.simpleName})")
            }
            try {
                if (method == "POST") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(0)
                    c.outputStream.close()
                }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().take(MAX_BODY).toByteArray().toString(Charsets.UTF_8) } ?: ""
                return when (code) {
                    200 -> body
                    401 -> throw RelayException(FailKind.AUTH_REJECTED, "Relay rejected the credential")
                    409 -> throw RelayException(FailKind.NONCE_REJECTED, "Relay rejected the challenge (expired or reused)")
                    429 -> throw RelayException(FailKind.LOCKED_OUT, "Relay is locked out after failed attempts")
                    else -> throw RelayException(FailKind.SERVER_ERROR, "Relay returned HTTP $code")
                }
            } catch (e: SocketTimeoutException) {
                throw RelayException(FailKind.NO_REPLY, "No reply from relay within ${timeoutMs / 1000}s")
            } catch (e: IOException) {
                throw RelayException(FailKind.NO_REPLY, "Connection lost before a reply (${e.javaClass.simpleName})")
            }
        } finally {
            c.disconnect()
        }
    }

    private companion object { const val MAX_BODY = 4096 }
}
