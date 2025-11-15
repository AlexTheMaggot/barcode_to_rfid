package com.example.barcodetorfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.device.ScanManager

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.rfid.trans.ReaderHelp
import com.rfid.trans.OtgUtils
import com.rfid.trans.TagCallback
import com.rfid.trans.ReadTag

import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : AppCompatActivity() {

    private lateinit var txtBarcode: TextView
    private lateinit var rrlib: ReaderHelp
    private var scanManager: ScanManager? = null
    private var currentBarcode: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isInventoryRunning = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScanManager.ACTION_DECODE) return
            val barcode = intent.getStringExtra(ScanManager.BARCODE_STRING_TAG) ?: return
            val trimmed = barcode.trim()
            currentBarcode = trimmed
            runOnUiThread {
                txtBarcode.text = "Barcode: $trimmed"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars()
        )
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        txtBarcode = findViewById(R.id.txtBarcode)

        rrlib = ReaderHelp()
        OtgUtils.set53GPIOEnabled(true)
        Thread.sleep(1500)

        val connectResult = rrlib.Connect("/dev/ttyHSL0", 115200, 1)
        if (connectResult == 0) {
            Log.d("rfid", "UHF connected")
        } else {
            Log.e("rfid", "UHF connect error: $connectResult")
        }

        rrlib.SetRfPower(30)

        val param = rrlib.GetInventoryPatameter()
        param.IvtType = 0
        param.Session = 0
        param.QValue = 4
        param.Antenna = 0x80
        param.ScanTime = 50
        rrlib.SetInventoryPatameter(param)

        scanManager = ScanManager()
        scanManager?.openScanner()
        scanManager?.switchOutputMode(0)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ScanManager.ACTION_DECODE)
        registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopInventoryIfRunning()
        scanManager?.closeScanner()
        rrlib.DisConnect()
        OtgUtils.set53GPIOEnabled(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == 523) {
            Log.d("rfid", "Trigger pressed")
            if (!isInventoryRunning) {
                performRfidAction()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun performRfidAction() {
        if (currentBarcode != null) {
            val original = currentBarcode!!.uppercase()
            if (!isValidHex(original)) {
                runOnUiThread { txtBarcode.text = "Ошибка: только 0-9 A-F" }
                return
            }
            if (original.length > 24) {
                runOnUiThread { txtBarcode.text = "Ошибка: слишком длинный (>24 символов)" }
                return
            }

            var padded = original
            val remainder = original.length % 4
            val added = if (remainder == 0) 0 else 4 - remainder
            if (added > 0) {
                padded = original.padStart(original.length + added, '0')
            }

            val writeRes = rrlib.WriteEPC_G2(padded, "00000000")
            if (writeRes != 0) {
                runOnUiThread { txtBarcode.text = "Ошибка записи: $writeRes" }
                return
            }

            startTemporaryInventory(800) { tags ->
                handleCollectedTags(tags, original, added)
            }
        } else {
            startTemporaryInventory(800) { tags ->
                handleCollectedTags(tags, null, 0)
            }
        }
    }

    private fun startTemporaryInventory(durationMs: Int, onComplete: (List<ReadTag>) -> Unit) {
        if (isInventoryRunning) return
        isInventoryRunning = true

        val collected = ConcurrentLinkedQueue<ReadTag>()

        val tempCallback = object : TagCallback {
            override fun tagCallback(tag: ReadTag) {
                collected.removeIf { it.epcId == tag.epcId }
                collected.add(tag)
                Log.d("rfid", "Tag found: ${tag.epcId}, RSSI: ${tag.rssi}")
            }

            override fun StopReadCallBack() {
                Log.d("rfid", "Inventory stopped")
            }
        }

        rrlib.SetCallBack(tempCallback)
        rrlib.StartRead()

        handler.postDelayed({
            rrlib.StopRead()
            isInventoryRunning = false
            onComplete(collected.toList())
        }, durationMs.toLong())
    }

    private fun handleCollectedTags(tags: List<ReadTag>, expectedOriginal: String?, added: Int) {
        runOnUiThread {
            if (tags.isEmpty()) {
                txtBarcode.text = "Метка не обнаружена"
                return@runOnUiThread
            }

            val selectedTag = tags.maxByOrNull { it.rssi }!!
            val readHex = selectedTag.epcId.uppercase()
            val stripped = stripLeadingZeros(readHex)

            if (tags.size > 1) {
                Log.d("rfid", "Multiple tags found (${tags.size}), selected strongest RSSI: ${selectedTag.rssi}")
            }

            if (expectedOriginal != null) {
                val expected = if (added % 2 == 1) "0$expectedOriginal" else expectedOriginal
                if (stripped == expected) {
                    txtBarcode.text = "УСПЕШНО! Записано: $expectedOriginal (RSSI: ${selectedTag.rssi})"
                } else {
                    txtBarcode.text = "НЕ СОВПАДАЕТ: $stripped (ожидалось $expected, RSSI: ${selectedTag.rssi})"
                }
            } else {
                txtBarcode.text = "EPC: $stripped (RSSI: ${selectedTag.rssi})"
            }
        }
    }

    private fun stopInventoryIfRunning() {
        if (isInventoryRunning) {
            rrlib.StopRead()
            isInventoryRunning = false
        }
    }

    private fun isValidHex(str: String): Boolean {
        return str.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun stripLeadingZeros(hex: String): String {
        var result = hex
        while (result.startsWith("00") && result.length >= 2) {
            result = result.substring(2)
        }
        if (result.isEmpty()) result = "0"
        return result
    }
}