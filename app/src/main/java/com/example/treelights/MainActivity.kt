package com.example.treelights

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private val BG = Color.parseColor("#101418")

class Strand {
    var socket: BluetoothSocket? = null
    var out: OutputStream? = null

    @Volatile
    var connected = false
    var onDrop: (() -> Unit)? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }

    fun send(data: ByteArray) {
        if (!connected) return
        exec.execute {
            try {
                out?.write(data)
                out?.flush()
            } catch (e: IOException) {
                connected = false
                onDrop?.invoke()
            }
        }
    }

    fun close() {
        connected = false
        try {
            socket?.close()
        } catch (e: IOException) {
        }
        socket = null
        out = null
    }
}

class Preset(val name: String, val r: Int, val g: Int, val b: Int)

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var btAdapter: BluetoothAdapter? = null
    private var devices: List<BluetoothDevice> = emptyList()

    private val strands = listOf(Strand(), Strand())
    private val nicks = ArrayList<EditText>()
    private val spinners = ArrayList<Spinner>()
    private val statusViews = ArrayList<TextView>()
    private val connectButtons = ArrayList<Button>()
    private val radios = ArrayList<RadioButton>()

    private lateinit var seekR: SeekBar
    private lateinit var seekG: SeekBar
    private lateinit var seekB: SeekBar
    private lateinit var swatch: View

    // Colour values run 0..15 on these lights.
    private var cr = 15
    private var cg = 0
    private var cb = 0
    private var brightPct = 100
    private var target = 2 // 0 = A, 1 = B, 2 = both

    @Volatile
    private var effectRun = false
    private var effectThread: Thread? = null

    // ---------- protocol ----------

    private fun brightByte(): Int = maxOf(5, brightPct * 0xCC / 100)

    // Whole strand: FF 28 <bright> <blue> <green> <red> 00 00 00 00 FF
    private fun frame(blue: Int, green: Int, red: Int, bright: Int): ByteArray {
        val f = ByteArray(11)
        f[0] = 0xFF.toByte()
        f[1] = 0x28.toByte()
        f[2] = bright.toByte()
        f[3] = blue.toByte()
        f[4] = green.toByte()
        f[5] = red.toByte()
        f[10] = 0xFF.toByte()
        return f
    }

    // Single bulb (UNTESTED guess): FF 06 <bulb> <bright> <blue> <green> <red> 00 00 00 FF
    private fun bulbFrame(index: Int, blue: Int, green: Int, red: Int, bright: Int): ByteArray {
        val f = ByteArray(11)
        f[0] = 0xFF.toByte()
        f[1] = 0x06.toByte()
        f[2] = index.toByte()
        f[3] = bright.toByte()
        f[4] = blue.toByte()
        f[5] = green.toByte()
        f[6] = red.toByte()
        f[10] = 0xFF.toByte()
        return f
    }

    private fun sendAll(data: ByteArray) {
        when (target) {
            0 -> strands[0].send(data)
            1 -> strands[1].send(data)
            else -> strands.forEach { it.send(data) }
        }
    }

    private fun showColor() {
        stopEffect()
        sendAll(frame(cb, cg, cr, brightByte()))
        swatch.setBackgroundColor(Color.rgb(cr * 17, cg * 17, cb * 17))
    }

    // ---------- effects ----------

    private fun stopEffect() {
        effectRun = false
        effectThread?.interrupt()
        effectThread = null
    }

    private fun startEffect(kind: String) {
        stopEffect()
        effectRun = true
        val t = Thread {
            try {
                when (kind) {
                    "rg" -> runAlternate(Preset("", 15, 0, 0), Preset("", 0, 15, 0))
                    "rw" -> runAlternate(Preset("", 15, 0, 0), Preset("", 15, 15, 15))
                    "rainbow" -> runRainbow()
                    "breathe" -> runBreathe()
                    "chase" -> runChase()
                    else -> {}
                }
            } catch (e: InterruptedException) {
            }
        }
        t.isDaemon = true
        effectThread = t
        t.start()
    }

    private fun runAlternate(a: Preset, b: Preset) {
        while (effectRun) {
            sendAll(frame(a.b, a.g, a.r, brightByte()))
            Thread.sleep(900)
            sendAll(frame(b.b, b.g, b.r, brightByte()))
            Thread.sleep(900)
        }
    }

    private fun runRainbow() {
        var h = 0f
        while (effectRun) {
            val c = Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            sendAll(frame(Color.blue(c) * 15 / 255, Color.green(c) * 15 / 255, Color.red(c) * 15 / 255, brightByte()))
            h = (h + 5f) % 360f
            Thread.sleep(120)
        }
    }

    private fun runBreathe() {
        val m = maxOf(cr, cg, cb, 1)
        val none = (cr + cg + cb) == 0
        val br = if (none) 15 else cr * 15 / m
        val bg = if (none) 0 else cg * 15 / m
        val bb = if (none) 0 else cb * 15 / m
        val steps = (2..15).toList() + (14 downTo 3).toList()
        while (effectRun) {
            for (k in steps) {
                if (!effectRun) return
                sendAll(frame((bb * k + 7) / 15, (bg * k + 7) / 15, (br * k + 7) / 15, brightByte()))
                Thread.sleep(90)
            }
        }
    }

    // Experimental: uses the untested single-bulb command (assumes 36 bulbs, numbered from 1).
    private fun runChase() {
        while (effectRun) {
            for (i in 1..36) {
                if (!effectRun) return
                sendAll(bulbFrame(i, 0, 0, 15, brightByte()))
                Thread.sleep(80)
                sendAll(bulbFrame(i, 0, 0, 0, brightByte()))
            }
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("treelights", Context.MODE_PRIVATE)
        btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        buildUi()
        for (i in 0..1) {
            strands[i].onDrop = { runOnUiThread { setStatus(i, false, "Connection lost") } }
        }
        if (hasPerm()) {
            refreshDevices()
        } else {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerm()) refreshDevices() else toast("Bluetooth permission is needed (Nearby devices).")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEffect()
        strands.forEach { it.close() }
    }

    private fun hasPerm(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- devices / connection ----------

    private fun refreshDevices() {
        val a = btAdapter
        if (a == null) {
            toast("This phone has no Bluetooth.")
            return
        }
        if (!hasPerm()) return
        val list = a.bondedDevices.toList().sortedWith(
            compareByDescending<BluetoothDevice> { it.name?.startsWith("00651") == true }.thenBy { it.name ?: "" }
        )
        devices = list
        val names = list.map { (it.name ?: "Unknown") + "  (" + it.address + ")" }
        for (i in 0..1) {
            val sp = spinners[i]
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val saved = prefs.getString("dev_$i", null)
            val idx = list.indexOfFirst { it.address == saved }
            if (idx >= 0) sp.setSelection(idx) else if (list.size > i) sp.setSelection(i)
        }
    }

    private fun setStatus(i: Int, ok: Boolean, msg: String) {
        statusViews[i].text = msg
        connectButtons[i].text = if (ok) "Disconnect" else "Connect"
    }

    private fun openSocket(dev: BluetoothDevice): Pair<BluetoothSocket?, String> {
        var err = ""
        try {
            val s = dev.createRfcommSocketToServiceRecord(SPP)
            try {
                s.connect()
                return Pair(s, "")
            } catch (e: Exception) {
                err = e.message ?: "connect failed"
                try {
                    s.close()
                } catch (e2: Exception) {
                }
            }
        } catch (e: Exception) {
            err = e.message ?: "socket failed"
        }
        // Fallback some serial-style devices need.
        try {
            val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s2 = m.invoke(dev, 1) as BluetoothSocket
            try {
                s2.connect()
                return Pair(s2, "")
            } catch (e: Exception) {
                err = e.message ?: err
                try {
                    s2.close()
                } catch (e2: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return Pair(null, err)
    }

    private fun toggleConnect(i: Int) {
        val s = strands[i]
        if (s.connected) {
            s.close()
            setStatus(i, false, "Disconnected")
            return
        }
        val idx = spinners[i].selectedItemPosition
        if (idx < 0 || idx >= devices.size) {
            toast("Pair the lights in Bluetooth settings, then tap Refresh list.")
            return
        }
        val dev = devices[idx]
        connectButtons[i].isEnabled = false
        statusViews[i].text = "Connecting..."
        Thread {
            btAdapter?.cancelDiscovery()
            val (sock, err) = openSocket(dev)
            runOnUiThread {
                connectButtons[i].isEnabled = true
                if (sock != null) {
                    s.socket = sock
                    s.out = sock.outputStream
                    s.connected = true
                    prefs.edit().putString("dev_$i", dev.address).apply()
                    setStatus(i, true, "Connected")
                } else {
                    setStatus(i, false, "Failed: $err")
                }
            }
        }.start()
    }

    // ---------- UI ----------

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun tv(t: String, size: Float, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(Color.WHITE)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        v.setPadding(0, dp(8), 0, dp(4))
        return v
    }

    private fun slider(col: LinearLayout, label: String, max: Int, init: Int, onChange: (Int) -> Unit): SeekBar {
        col.addView(tv(label, 13f, false))
        val sb = SeekBar(this)
        sb.max = max
        sb.progress = init
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        col.addView(sb)
        return sb
    }

    private fun nameOf(i: Int): String {
        val n = nicks[i].text.toString().trim()
        return if (n.isEmpty()) (if (i == 0) "Set A" else "Set B") else n
    }

    private fun updateRadioLabels() {
        radios[0].text = nameOf(0)
        radios[1].text = nameOf(1)
        radios[2].text = "Both"
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(40))
        scroll.addView(col)

        col.addView(tv("Tree Lights", 26f, true))
        col.addView(tv("Pair each light set in Android's Bluetooth settings first, then pick it below.", 13f, false))

        for (i in 0..1) {
            val key = if (i == 0) "A" else "B"
            col.addView(tv("Light set $key", 16f, true))
            val nick = EditText(this)
            nick.hint = "Nickname (e.g. Tree)"
            nick.setText(prefs.getString("nick_$key", ""))
            nick.setTextColor(Color.WHITE)
            nick.setHintTextColor(Color.GRAY)
            nick.setSingleLine(true)
            nicks.add(nick)
            col.addView(nick)

            val sp = Spinner(this)
            spinners.add(sp)
            col.addView(sp)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val btn = Button(this)
            btn.text = "Connect"
            btn.setOnClickListener { toggleConnect(i) }
            connectButtons.add(btn)
            val st = tv("Not connected", 14f, false)
            st.setPadding(dp(12), 0, 0, 0)
            statusViews.add(st)
            row.addView(btn)
            row.addView(st)
            col.addView(row)
        }

        val refresh = Button(this)
        refresh.text = "Refresh paired list"
        refresh.setOnClickListener { if (hasPerm()) refreshDevices() else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
        col.addView(refresh)

        col.addView(tv("Control", 18f, true))
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        for (k in 0..2) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.setTextColor(Color.WHITE)
            radios.add(rb)
            rg.addView(rb)
        }
        updateRadioLabels()
        radios[2].isChecked = true
        for (k in 0..2) {
            radios[k].setOnCheckedChangeListener { _, checked -> if (checked) target = k }
        }
        col.addView(rg)
        for (i in 0..1) {
            val key = if (i == 0) "A" else "B"
            nicks[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("nick_$key", s?.toString() ?: "").apply()
                    updateRadioLabels()
                }
            })
        }

        swatch = View(this)
        swatch.setBackgroundColor(Color.rgb(cr * 17, cg * 17, cb * 17))
        col.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))

        col.addView(tv("Colors", 18f, true))
        val presets = listOf(
            Preset("Red", 15, 0, 0), Preset("Orange", 15, 8, 0), Preset("Warm", 15, 9, 3),
            Preset("Green", 0, 15, 0), Preset("Cyan", 0, 15, 15), Preset("Blue", 0, 0, 15),
            Preset("Purple", 9, 0, 15), Preset("Pink", 15, 2, 8), Preset("White", 15, 15, 15),
            Preset("Off", 0, 0, 0)
        )
        presets.chunked(3).forEach { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { p ->
                val bt = Button(this)
                bt.text = p.name
                val sum = p.r + p.g + p.b
                bt.setBackgroundColor(if (sum == 0) Color.DKGRAY else Color.rgb(p.r * 17, p.g * 17, p.b * 17))
                bt.setTextColor(if (sum > 20) Color.BLACK else Color.WHITE)
                bt.setOnClickListener {
                    cr = p.r
                    cg = p.g
                    cb = p.b
                    seekR.progress = cr
                    seekG.progress = cg
                    seekB.progress = cb
                    showColor()
                }
                row.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(row)
        }

        col.addView(tv("Mix your own", 18f, true))
        seekR = slider(col, "Red", 15, cr) { cr = it; showColor() }
        seekG = slider(col, "Green", 15, cg) { cg = it; showColor() }
        seekB = slider(col, "Blue", 15, cb) { cb = it; showColor() }
        slider(col, "Brightness (untested)", 100, brightPct) { brightPct = it; showColor() }

        col.addView(tv("Effects", 18f, true))
        val effects = listOf(
            Pair("Red & Green", "rg"), Pair("Red & White", "rw"), Pair("Rainbow", "rainbow"),
            Pair("Breathe", "breathe"), Pair("Chase (experimental)", "chase")
        )
        effects.chunked(2).forEach { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { e ->
                val bt = Button(this)
                bt.text = e.first
                bt.setOnClickListener { startEffect(e.second) }
                row.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(row)
        }
        val stop = Button(this)
        stop.text = "Stop effect"
        stop.setOnClickListener { stopEffect() }
        col.addView(stop)

        col.addView(tv("Breathe uses the color you last picked. Chase and brightness use commands we haven't tested on your lights yet.", 12f, false))

        setContentView(scroll)
    }
}
