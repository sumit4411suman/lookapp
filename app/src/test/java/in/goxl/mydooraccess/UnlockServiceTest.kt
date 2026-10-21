package `in`.goxl.mydooraccess

import org.junit.Assert.*
import org.junit.Test

private class FakeApi : RelayApi {
    var challengeError: RelayException? = null
    var unlockResult: () -> UnlockReply = { UnlockReply(true, true, DoorSensor.NONE) }
    var statuses: ArrayDeque<DoorSensor> = ArrayDeque()
    var unlockCalls = 0
    override fun challenge(): String { challengeError?.let { throw it }; return "00112233445566778899aabbccddeeff" }
    override fun unlock(nonce: String, macHex: String): UnlockReply { unlockCalls++; return unlockResult() }
    override fun status(): RelayStatus = RelayStatus(true, statuses.removeFirstOrNull() ?: DoorSensor.CLOSED, "t")
}

class UnlockServiceTest {
    private val ok: (ByteArray) -> ByteArray? = { ByteArray(32) }
    private fun service(api: RelayApi, test: Boolean = false) = UnlockService(api, { test }, { })

    @Test fun signingInputAndVectorMatchFirmwareSpec() {
        val key = ByteArray(32) { it.toByte() }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(Wire.signingInput("00112233445566778899aabbccddeeff"))
        assertEquals("9f20af9efbd5c130f5bd8aaabc6745cbbcc30d33459b7e30445ba0458aaf5e45", mac.toHex())
    }

    @Test fun testModeCannotUnlock() {
        val api = FakeApi()
        assertThrows(IllegalStateException::class.java) { service(api, test = true).unlock(ok) }
        assertEquals(0, api.unlockCalls)
    }

    @Test fun testConnectionNeverCallsUnlock() {
        val api = FakeApi()
        assertTrue(service(api, test = true).testConnection() is TestResult.Reachable)
        assertEquals(0, api.unlockCalls)
    }

    @Test fun offlineFailsBeforeAskingUser() {
        val api = FakeApi().apply { challengeError = RelayException(FailKind.OFFLINE, "x") }
        var asked = false
        val r = service(api).unlock { asked = true; ByteArray(32) }
        assertEquals(FailKind.OFFLINE, (r as UnlockResult.Failed).kind)
        assertFalse(asked); assertEquals(0, api.unlockCalls)
    }

    @Test fun cancelledAuthSendsNothing() {
        val api = FakeApi()
        assertEquals(UnlockResult.Cancelled, service(api).unlock { null })
        assertEquals(0, api.unlockCalls)
    }

    @Test fun rejectedCredentialIsFailure() {
        val api = FakeApi().apply { unlockResult = { throw RelayException(FailKind.AUTH_REJECTED, "no") } }
        assertEquals(FailKind.AUTH_REJECTED, (service(api).unlock(ok) as UnlockResult.Failed).kind)
    }

    @Test fun replyWithAcceptedFalseIsFailure() {
        val api = FakeApi().apply { unlockResult = { UnlockReply(false, false, DoorSensor.NONE) } }
        assertTrue(service(api).unlock(ok) is UnlockResult.Failed)
    }

    @Test fun acceptedButContactNotActiveIsFailure() {
        val api = FakeApi().apply { unlockResult = { UnlockReply(true, false, DoorSensor.NONE) } }
        assertTrue(service(api).unlock(ok) is UnlockResult.Failed)
    }

    @Test fun timeoutAfterSendIsUnknownNotSuccessNorFailure() {
        val api = FakeApi().apply { unlockResult = { throw RelayException(FailKind.NO_REPLY, "t") } }
        assertTrue(service(api).unlock(ok) is UnlockResult.SentNoReply)
    }

    @Test fun noSensorNeverClaimsDoorOpened() {
        assertEquals(UnlockResult.Accepted(null), service(FakeApi()).unlock(ok))
    }

    @Test fun sensorSeesDoorOpen() {
        val api = FakeApi().apply {
            unlockResult = { UnlockReply(true, true, DoorSensor.CLOSED) }
            statuses = ArrayDeque(listOf(DoorSensor.CLOSED, DoorSensor.OPEN))
        }
        assertEquals(UnlockResult.Accepted(true), service(api).unlock(ok))
    }

    @Test fun sensorNeverSeesDoorOpen() {
        val api = FakeApi().apply { unlockResult = { UnlockReply(true, true, DoorSensor.CLOSED) } }
        assertEquals(UnlockResult.Accepted(false), service(api).unlock(ok))
    }

    @Test fun parsersRejectGarbage() {
        assertThrows(RelayException::class.java) { Wire.parseChallenge("{\"nonce\":\"zz\"}") }
        assertThrows(RelayException::class.java) { Wire.parseUnlock("{}") }
        assertEquals(DoorSensor.OPEN, Wire.parseStatus("{\"relay\":\"idle\",\"door\":\"open\",\"fw\":\"1\"}").door)
    }
}
