package app.simgate.ui

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ToggleButton
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.simgate.data.t
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Full-screen QR scanner for the "connect to SMS server" flow. View-based (zxing-android-embedded),
 * shown over the Compose UI. It returns the raw scanned/pasted text — parsing and the confirmation
 * screen live in [ConnectFlow]; nothing is applied here.
 *
 * The CAMERA permission is requested only when this screen opens (i.e. after the user taps "Scan"),
 * and a denial is not fatal: the screen returns [EXTRA_MANUAL] so the caller falls back to typing.
 */
class QrScannerActivity : ComponentActivity() {
    private lateinit var barcode: DecoratedBarcodeView
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        barcode = DecoratedBarcodeView(this).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText(t("Наведите камеру на QR-код из мастера сервера", "Point the camera at the QR code from the server wizard"))
        }

        val torch = ToggleButton(this).apply {
            textOn = t("Фонарик вкл.", "Torch on"); textOff = t("Фонарик", "Torch"); isChecked = false
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#33000000"))
            visibility = if (hasFlash()) ViewGroup.VISIBLE else ViewGroup.GONE
            setOnCheckedChangeListener { _, on -> if (on) barcode.setTorchOn() else barcode.setTorchOff() }
        }
        val paste = actionButton(t("Вставить из буфера", "Paste from clipboard")) {
            val text = clipboardText()
            if (text.isNullOrBlank()) barcode.setStatusText(t("Буфер обмена пуст", "Clipboard is empty"))
            else finishWith(text, manual = false)
        }
        val manual = actionButton(t("Ввести вручную", "Enter manually")) { finishWith(null, manual = true) }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            addView(paste); addView(manual)
        }
        setContentView(FrameLayout(this).apply {
            addView(barcode, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(torch, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(16), dp(16), 0) })
            addView(controls, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })
        })

        if (hasCamera()) startScanning() else requestCamera()
    }

    private fun startScanning() {
        barcode.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (handled) return
                result.text?.takeIf { it.isNotBlank() }?.let { finishWith(it, manual = false) }
            }
        })
    }

    private fun finishWith(text: String?, manual: Boolean) {
        if (handled) return
        handled = true
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, text).putExtra(EXTRA_MANUAL, manual))
        finish()
    }

    private fun requestCamera() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { startScanning(); barcode.resume() }
        else finishWith(null, manual = true) // denied → let the caller fall back to manual entry
    }

    override fun onResume() { super.onResume(); if (hasCamera()) barcode.resume() }
    override fun onPause() { super.onPause(); barcode.pause() }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasFlash() = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    private fun clipboardText() = getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_MANUAL = "manual"
        private const val REQ_CAMERA = 701
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(ctx: Context) = Intent(ctx, QrScannerActivity::class.java)
    }
}
