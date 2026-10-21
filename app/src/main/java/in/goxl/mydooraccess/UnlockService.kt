package `in`.goxl.mydooraccess

sealed class TestResult {
    data class Reachable(val status: RelayStatus) : TestResult()
    data class Failed(val kind: FailKind, val detail: String) : TestResult()
}

sealed class UnlockResult {
    object Cancelled : UnlockResult()
    /** Nothing was sent as a command, or the relay refused it. */
    data class Failed(val kind: FailKind, val detail: String) : UnlockResult()
    /** The command was written but no reply arrived. The door state is unknown. */
    data class SentNoReply(val detail: String) : UnlockResult()
    /**
     * The relay authenticated the command and reports its contact active.
     * [doorOpenedObserved]: true = door sensor saw the door open, false = sensor did not see it,
     * null = no door sensor fitted. None of these proves the lock itself released.
     */
    data class Accepted(val doorOpenedObserved: Boolean?) : UnlockResult()
}

class UnlockService(
    private val api: RelayApi,
    private val isTestMode: () -> Boolean,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun testConnection(): TestResult = try {
        api.challenge()
        TestResult.Reachable(api.status())
    } catch (e: RelayException) {
        TestResult.Failed(e.kind, e.message ?: e.kind.name)
    }

    /**
     * [authorize] receives the bytes to sign, asks the user to authenticate, and returns the HMAC
     * or null if the user cancelled or authentication failed.
     */
    fun unlock(authorize: (ByteArray) -> ByteArray?): UnlockResult {
        check(!isTestMode()) { "Unlock requested while test mode is on" }
        val nonce = try {
            api.challenge()
        } catch (e: RelayException) {
            return UnlockResult.Failed(e.kind, e.message ?: e.kind.name)
        }
        val mac = authorize(Wire.signingInput(nonce)) ?: return UnlockResult.Cancelled
        val reply = try {
            api.unlock(nonce, mac.toHex())
        } catch (e: RelayException) {
            return if (e.kind == FailKind.NO_REPLY) UnlockResult.SentNoReply(e.message ?: "No reply")
            else UnlockResult.Failed(e.kind, e.message ?: e.kind.name)
        }
        if (!reply.accepted) return UnlockResult.Failed(FailKind.AUTH_REJECTED, "Relay replied but did not accept the command")
        if (!reply.relayActive) return UnlockResult.Failed(FailKind.BAD_REPLY, "Relay accepted but does not report its contact active")
        if (reply.door == DoorSensor.NONE) return UnlockResult.Accepted(null)
        if (reply.door == DoorSensor.OPEN) return UnlockResult.Accepted(true)
        repeat(DOOR_POLLS) {
            sleepMs(DOOR_POLL_MS)
            val s = try { api.status() } catch (e: RelayException) { return UnlockResult.Accepted(false) }
            if (s.door == DoorSensor.OPEN) return UnlockResult.Accepted(true)
        }
        return UnlockResult.Accepted(false)
    }

    private companion object {
        const val DOOR_POLLS = 10
        const val DOOR_POLL_MS = 500L
    }
}
