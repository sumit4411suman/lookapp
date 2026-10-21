package `in`.goxl.mydooraccess

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var prefs: SharedPreferences
    private val io = Executors.newSingleThreadExecutor()
    private var nfc: NfcAdapter? = null

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var diagView: TextView
    private lateinit var actionBtn: Button
    private lateinit var testBox: CheckBox
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var pinEt: EditText
    private lateinit var secretEt: EditText
    @Volatile private var testMode = true
    @Volatile private var authError: String? = null
    private var controllerLine = "Controller: not checked"
    private var cardReport = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        nfc = NfcAdapter.getDefaultAdapter(this)
        actionBar?.hide()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 48) }
        root.addView(text("My Door Access", 24f))
        statusView = text("", 14f).also { root.addView(it) }

        testBox = CheckBox(this).apply {
            text = "Test mode (never unlocks)"
            isChecked = true
            setOnCheckedChangeListener { _, on -> testMode = on; refresh() }
        }
        root.addView(testBox)
        actionBtn = Button(this).apply { setOnClickListener { onAction() } }
        root.addView(actionBtn)
        resultView = text("", 15f).also { root.addView(it) }

        root.addView(Button(this).apply {
            text = "NFC Tap Access"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("NFC Tap Access — not available")
                    .setMessage("The Olympia card was not readable at 13.56 MHz, so the existing reader is almost certainly 125 kHz. " +
                        "A phone cannot emulate a 125 kHz card, so tap-to-unlock is not possible with this reader. Use Unlock Door instead.")
                    .setPositiveButton("OK", null).show()
            }
        })

        root.addView(text("\nSetup", 18f))
        root.addView(text("1. Fit the relay (see docs/DESIGN.md). 2. Enter the address, certificate pin and secret printed by tools/gen_secrets.sh. " +
            "3. Save, then run Test connection. 4. Only then untick Test mode.", 13f))
        hostEt = edit("Relay IP address", prefs.getString("host", "") ?: "", InputType.TYPE_CLASS_TEXT).also { root.addView(it) }
        portEt = edit("Port", prefs.getInt("port", 8443).toString(), InputType.TYPE_CLASS_NUMBER).also { root.addView(it) }
        pinEt = edit("Certificate SHA-256 pin (64 hex)", prefs.getString("pin", "") ?: "", InputType.TYPE_CLASS_TEXT).also { root.addView(it) }
        secretEt = edit("Shared secret (64 hex) — stored in Keystore, never shown again", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).also { root.addView(it) }
        root.addView(Button(this).apply { text = "Save setup"; setOnClickListener { saveSetup() } })

        root.addView(text("\nDiagnostics", 18f))
        diagView = text("", 12f).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }.also { root.addView(it) }

        val scroll = ScrollView(this).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 36) {
            try { requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 1) } catch (_: Exception) {}
        }
        refresh()
    }

    private fun text(t: String, size: Float) = TextView(this).apply { text = t; textSize = size; setPadding(0, 8, 0, 8) }
    private fun edit(hint: String, value: String, type: Int) = EditText(this).apply { this.hint = hint; setText(value); inputType = type; setSingleLine() }

    private fun configured() = (prefs.getString("host", "") ?: "").isNotBlank() &&
        (prefs.getString("pin", "") ?: "").length == 64 && KeystoreSigner.hasKey()

    private fun refresh() {
        val pm = packageManager
        statusView.text = "NFC: " + (if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC)) "no hardware" else if (nfc?.isEnabled == true) "on" else "off") +
            "\n$controllerLine\nSetup: " + (if (configured()) "complete" else "incomplete")
        actionBtn.text = if (testMode) "Test connection" else "Unlock Door"
        actionBtn.isEnabled = configured()
        diagView.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "HCE (ISO-DEP): ${pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)}\n" +
            "Mode: " + (if (testMode) "TEST (unlock disabled)" else "LIVE") + "\n" +
            "Card check: hold a card to the back of the phone.\n$cardReport"
    }

    private fun saveSetup() {
        val pin = pinEt.text.toString().trim().lowercase()
        val secret = secretEt.text.toString().trim()
        if (pin.length != 64 || !pin.all { it in '0'..'9' || it in 'a'..'f' }) { resultView.text = "Pin must be 64 hex characters."; return }
        prefs.edit().putString("host", hostEt.text.toString().trim()).putInt("port", portEt.text.toString().toIntOrNull() ?: 8443).putString("pin", pin).apply()
        if (secret.isNotEmpty()) {
            try {
                val bytes = secret.hexToBytes()
                if (bytes.size != 32) throw IllegalArgumentException("need 64 hex characters")
                KeystoreSigner.importSecret(bytes); bytes.fill(0)
                secretEt.setText("")
            } catch (e: Exception) { resultView.text = "Secret not saved: ${e.message}. A screen lock or biometric must be set up."; return }
        }
        resultView.text = "Saved."
        refresh()
    }

    private fun service() = UnlockService(
        HttpsRelayApi(prefs.getString("host", "")!!, prefs.getInt("port", 8443), prefs.getString("pin", "")!!),
        { testMode },
    )

    private fun onAction() {
        if (testMode) {
            resultView.text = "Testing connection…"
            io.execute {
                val r = service().testConnection()
                runOnUiThread {
                    when (r) {
                        is TestResult.Reachable -> {
                            controllerLine = "Controller: reachable, certificate pin OK (fw ${r.status.firmware}, door sensor: ${r.status.door.name.lowercase()})"
                            resultView.text = "Connection test passed. Nothing was unlocked."
                        }
                        is TestResult.Failed -> {
                            controllerLine = "Controller: ${r.kind.name.lowercase()}"
                            resultView.text = "Connection test failed: ${r.detail}" + localNetworkHint(r.kind)
                        }
                    }
                    refresh()
                }
            }
        } else {
            AlertDialog.Builder(this).setTitle("Unlock the door now?").setMessage("This triggers the door relay.")
                .setNegativeButton("Cancel", null).setPositiveButton("Unlock") { _, _ -> runUnlock() }.show()
        }
    }

    private fun localNetworkHint(k: FailKind) =
        if (k == FailKind.OFFLINE && Build.VERSION.SDK_INT >= 36) "\nIf this keeps failing, check that the app has Local network access in its system settings." else ""

    private fun runUnlock() {
        resultView.text = "Contacting relay…"
        io.execute {
            val r = service().unlock { authorizeBlocking(it) }
            runOnUiThread { resultView.text = describe(r); refresh() }
        }
    }

    private fun describe(r: UnlockResult): String = when (r) {
        UnlockResult.Cancelled -> "Cancelled" + (authError?.let { ": $it" } ?: "") + ". Nothing was sent."
        is UnlockResult.Failed -> "FAILED (${r.kind.name.lowercase()}): ${r.detail}. The door was not commanded." + localNetworkHint(r.kind)
        is UnlockResult.SentNoReply -> "UNKNOWN: the command was sent but ${r.detail.lowercase()}. Do not assume the door is locked or unlocked."
        is UnlockResult.Accepted -> "Command sent and accepted: the relay authenticated it and activated its contact.\n" + when (r.doorOpenedObserved) {
            true -> "Door sensor reports the door opened."
            false -> "Door sensor has NOT reported the door opening. Check the door."
            null -> "No door sensor is fitted, so physical unlock is not confirmed. Check the door."
        }
    }

    /** Runs on the I/O thread: shows the system prompt on the UI thread and waits for the user. */
    private fun authorizeBlocking(msg: ByteArray): ByteArray? {
        val out = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)
        authError = null
        runOnUiThread {
            try {
                val prompt = BiometricPrompt.Builder(this)
                    .setTitle("Confirm door unlock")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(BiometricPrompt.CryptoObject(KeystoreSigner.newMac()), CancellationSignal(), mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            try { out.set(result.cryptoObject?.mac?.doFinal(msg)) } catch (e: Exception) { authError = e.message }
                            latch.countDown()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { authError = errString?.toString(); latch.countDown() }
                    })
            } catch (e: Exception) {
                authError = "key unavailable (${e.javaClass.simpleName}); re-save the secret in Setup"
                latch.countDown()
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        return out.get()
    }

    override fun onResume() {
        super.onResume()
        nfc?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        refresh()
    }

    override fun onPause() { super.onPause(); nfc?.disableReaderMode(this) }

    override fun onTagDiscovered(tag: Tag) {
        val s = StringBuilder("Card detected (13.56 MHz)\nUID: ${tag.id.toHex()}\nTech: ${tag.techList.joinToString { it.substringAfterLast('.') }}\n")
        NfcA.get(tag)?.let { s.appendLine("ATQA: ${it.atqa.toHex()} SAK: 0x%02X".format(it.sak.toInt() and 0xFF)) }
        MifareClassic.get(tag)?.let { s.appendLine("MIFARE Classic size=${it.size}") }
        MifareUltralight.get(tag)?.let { s.appendLine("MIFARE Ultralight type=${it.type}") }
        IsoDep.get(tag)?.let { s.appendLine("ISO-DEP historical=${it.historicalBytes?.toHex()}") }
        s.appendLine("No keys tried; no data read.")
        runOnUiThread { cardReport = s.toString(); refresh() }
    }
}
