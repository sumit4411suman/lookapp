package `in`.goxl.mydooraccess

enum class FailKind { OFFLINE, TLS_FAILED, AUTH_REJECTED, NONCE_REJECTED, LOCKED_OUT, SERVER_ERROR, BAD_REPLY, NO_REPLY }

class RelayException(val kind: FailKind, message: String) : Exception(message)

enum class DoorSensor { OPEN, CLOSED, NONE }

data class RelayStatus(val relayActive: Boolean, val door: DoorSensor, val firmware: String)
data class UnlockReply(val accepted: Boolean, val relayActive: Boolean, val door: DoorSensor)

/** Transport to the relay controller. Implementations must throw [RelayException] on every failure. */
interface RelayApi {
    fun challenge(): String
    fun unlock(nonce: String, macHex: String): UnlockReply
    fun status(): RelayStatus
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Wire format shared with firmware/relay/relay.ino. See docs/DESIGN.md. */
object Wire {
    fun signingInput(nonceHex: String): ByteArray = "unlock|$nonceHex".toByteArray(Charsets.UTF_8)

    private fun field(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"[^\"]*\"|true|false)").find(json)?.groupValues?.get(1)?.trim('"')

    private fun door(json: String): DoorSensor = when (field(json, "door")) {
        "open" -> DoorSensor.OPEN
        "closed" -> DoorSensor.CLOSED
        "none" -> DoorSensor.NONE
        else -> throw RelayException(FailKind.BAD_REPLY, "Reply has no valid door field")
    }

    private fun relay(json: String): Boolean = when (field(json, "relay")) {
        "active" -> true
        "idle" -> false
        else -> throw RelayException(FailKind.BAD_REPLY, "Reply has no valid relay field")
    }

    fun parseChallenge(json: String): String {
        val n = field(json, "nonce")
        if (n == null || n.length != 32 || !n.all { it in '0'..'9' || it in 'a'..'f' })
            throw RelayException(FailKind.BAD_REPLY, "Reply has no valid nonce")
        return n
    }

    fun parseUnlock(json: String): UnlockReply {
        val accepted = field(json, "accepted") ?: throw RelayException(FailKind.BAD_REPLY, "Reply has no accepted field")
        return UnlockReply(accepted == "true", relay(json), door(json))
    }

    fun parseStatus(json: String): RelayStatus =
        RelayStatus(relay(json), door(json), field(json, "fw") ?: throw RelayException(FailKind.BAD_REPLY, "Reply has no fw field"))
}
