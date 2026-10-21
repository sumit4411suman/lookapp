package `in`.goxl.mydooraccess

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Runs the real client against a local TLS server with a throw-away self-signed certificate. */
class HttpsRelayApiTest {
    private lateinit var server: SSLServerSocket
    private lateinit var pin: String
    private var port = 0

    @Before fun start() {
        val dir = Files.createTempDirectory("relaytest").toFile()
        val p12 = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val rc = ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=relay",
            "-validity", "2", "-keystore", p12.path, "-storetype", "PKCS12", "-storepass", "changeit", "-ext", "san=ip:127.0.0.1")
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        assertEquals(0, rc)
        val ks = KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, "changeit".toCharArray()) } }
        pin = MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("t").encoded).toHex()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        port = server.localPort
        Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { sock ->
                        val head = sock.getInputStream().bufferedReader().let { r ->
                            val first = r.readLine() ?: ""
                            while (true) { val l = r.readLine(); if (l == null || l.isEmpty()) break }
                            first
                        }
                        val (status, body) = when {
                            head.startsWith("GET /challenge") -> "200 OK" to "{\"nonce\":\"00112233445566778899aabbccddeeff\"}"
                            head.startsWith("POST /unlock") -> "401 Unauthorized" to ""
                            else -> "404 Not Found" to ""
                        }
                        sock.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    }
                } catch (e: Exception) {
                    // handshake aborted by a client that refused our certificate, or socket closed
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() { server.close() }

    @Test fun correctPinConnects() {
        assertEquals("00112233445566778899aabbccddeeff", HttpsRelayApi("127.0.0.1", port, pin).challenge())
    }

    @Test fun wrongPinIsRefusedAsTlsFailure() {
        val e = assertThrows(RelayException::class.java) { HttpsRelayApi("127.0.0.1", port, "00".repeat(32)).challenge() }
        assertEquals(FailKind.TLS_FAILED, e.kind)
    }

    @Test fun http401IsAuthRejected() {
        val e = assertThrows(RelayException::class.java) { HttpsRelayApi("127.0.0.1", port, pin).unlock("00112233445566778899aabbccddeeff", "00".repeat(32)) }
        assertEquals(FailKind.AUTH_REJECTED, e.kind)
    }

    @Test fun closedPortIsOffline() {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val e = assertThrows(RelayException::class.java) { HttpsRelayApi("127.0.0.1", closed, pin).challenge() }
        assertEquals(FailKind.OFFLINE, e.kind)
    }
}
