package `in`.goxl.mydooraccess

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Runs the real client against a local TLS server with a throw-away self-signed certificate. */
class HttpsRelayApiTest {
    private lateinit var server: HttpsServer
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
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ctx)
        server.createContext("/challenge") { ex ->
            val b = "{\"nonce\":\"00112233445566778899aabbccddeeff\"}".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/unlock") { ex ->
            ex.sendResponseHeaders(401, -1); ex.close()
        }
        server.start()
        port = server.address.port
    }

    @After fun stop() { server.stop(0) }

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
