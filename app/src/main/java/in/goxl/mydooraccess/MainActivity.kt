package `in`.goxl.mydooraccess

import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Phase 1 diagnostic. It only OBSERVES: device NFC/HCE capability and what technology a
 * physical card exposes at 13.56 MHz. It never authenticates to a card, never emulates one,
 * and never sends anything to a door controller.
 */
class MainActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var report: TextView
    private var nfc: NfcAdapter? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        nfc = NfcAdapter.getDefaultAdapter(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        root.addView(TextView(this).apply { text = "My Door Access — diagnostics"; textSize = 20f })
        val unlock = Button(this).apply {
            text = "Unlock Door (not configured)"
            isEnabled = false
        }
        root.addView(unlock)
        root.addView(TextView(this).apply {
            text = "Unlock is disabled: no authorized, encrypted controller interface has been " +
                "identified or tested. Nothing is sent to any door."
        })
        report = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(report)
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        refreshCapabilities()
    }

    private val capabilities = StringBuilder()

    private fun refreshCapabilities() {
        val pm = packageManager
        capabilities.clear()
        capabilities.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        capabilities.appendLine("NFC hardware: ${pm.hasSystemFeature(PackageManager.FEATURE_NFC)}")
        capabilities.appendLine("NFC enabled: ${nfc?.isEnabled}")
        capabilities.appendLine("HCE (ISO-DEP, Type A/B): ${pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)}")
        capabilities.appendLine("HCE-F (FeliCa): ${pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)}")
        capabilities.appendLine("Note: HCE cannot emulate MIFARE Classic or 125 kHz cards.")
        capabilities.appendLine()
        capabilities.appendLine("Hold the physical Olympia card against the back of the phone.")
        capabilities.appendLine("If nothing appears after ~10 s, the card is probably 125 kHz (the phone cannot read it).")
        report.text = capabilities
    }

    override fun onResume() {
        super.onResume()
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null
        )
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val s = StringBuilder(capabilities).appendLine().appendLine("=== Card detected (13.56 MHz) ===")
        s.appendLine("UID: ${tag.id.hex()}  (${tag.id.size} bytes)")
        s.appendLine("Technologies: ${tag.techList.joinToString { it.substringAfterLast('.') }}")
        NfcA.get(tag)?.let { s.appendLine("ATQA: ${it.atqa.hex()}  SAK: 0x%02X".format(it.sak.toInt() and 0xFF)) }
        MifareClassic.get(tag)?.let { s.appendLine("MIFARE Classic type=${it.type} size=${it.size} bytes (sectors=${it.sectorCount})") }
        MifareUltralight.get(tag)?.let { s.appendLine("MIFARE Ultralight type=${it.type}") }
        IsoDep.get(tag)?.let { s.appendLine("ISO-DEP: historical=${it.historicalBytes?.hex()} hiLayer=${it.hiLayerResponse?.hex()}") }
        s.appendLine("No keys were tried and no data blocks were read.")
        runOnUiThread { report.text = s }
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
