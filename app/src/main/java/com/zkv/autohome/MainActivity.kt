package com.zkv.autohome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var connStatus: TextView
    private lateinit var editor: EditText
    private lateinit var layoutStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val PICK = 1
    private val SAVE = 2

    private val BG = Color.rgb(24, 26, 32)
    private val CARD = Color.rgb(32, 35, 42)
    private val INPUT = Color.rgb(42, 46, 55)
    private val ACCENT = Color.rgb(54, 201, 141)
    private val GREY = Color.rgb(58, 63, 73)
    private val MUTED = Color.rgb(150, 156, 168)
    private val RED = Color.rgb(214, 90, 90)
    private val AMBER = Color.rgb(230, 170, 60)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun configFile() = File(getExternalFilesDir(null), "autohome.yaml")

    private fun layoutText(): String {
        val f = configFile()
        return if (f.exists()) f.readText()
        else try { assets.open("autohome.yaml").bufferedReader().use { it.readText() } }
        catch (e: Exception) { try { assets.open("autohome.example.yaml").bufferedReader().use { it.readText() } } catch (e2: Exception) { "" } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply {
            text = "AutoHome"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })

        // ---------- Connection card ----------
        val conn = card()
        conn.addView(header("Home Assistant"))
        urlField = field("https://ha.local:8123  /  https://...nabu.casa", Settings.url(this), false)
        conn.addView(urlField)
        tokenField = field("Long-Lived Access Token", Settings.token(this), true)
        conn.addView(tokenField)
        conn.addView(CheckBox(this).apply {
            text = "Show token"; setTextColor(MUTED)
            setOnCheckedChangeListener { _, on ->
                tokenField.transformationMethod = if (on) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                tokenField.setSelection(tokenField.text.length)
            }
        })
        val connBtns = row()
        connBtns.addView(btn("Test", ACCENT) { testConnection() })
        connBtns.addView(btn("Save", GREY) { saveConnection() })
        conn.addView(connBtns)
        connStatus = status()
        conn.addView(connStatus)
        root.addView(conn)

        // ---------- Layout card ----------
        val lay = card()
        lay.addView(header("Layout (YAML)"))
        lay.addView(TextView(this).apply {
            text = "Tabs & widgets. Connection is stored separately above."
            textSize = 11f; setTextColor(MUTED); setPadding(0, 0, 0, dp(8))
        })
        editor = EditText(this).apply {
            setText(layoutText()); textSize = 12f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP; setHorizontallyScrolling(true)
            setTextColor(Color.WHITE); background = rounded(INPUT, 8); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        lay.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        val layBtns = row()
        layBtns.addView(btn("Save", ACCENT) { saveLayout() })
        layBtns.addView(btn("Validate", GREY) { validate() })
        layBtns.addView(btn("Reset", GREY) { resetLayout() })
        lay.addView(layBtns)
        val fileBtns = row()
        fileBtns.addView(btn("Load file", GREY) { pickFile() })
        fileBtns.addView(btn("Export", GREY) { exportFile() })
        lay.addView(fileBtns)
        layoutStatus = status()
        lay.addView(layoutStatus)
        root.addView(lay)

        root.addView(TextView(this).apply {
            text = "After saving, restart Android Auto to reload."
            textSize = 11f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    // ---------- UI helpers ----------
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 14); setPadding(dp(14), dp(12), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14); layoutParams = lp
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }

    private fun field(hint: String, value: String, password: Boolean) = EditText(this).apply {
        setText(value); this.hint = hint; setHintTextColor(MUTED); setTextColor(Color.WHITE)
        textSize = 14f; background = rounded(INPUT, 8); setPadding(dp(10), dp(10), dp(10), dp(10))
        isSingleLine = true
        if (password) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8); layoutParams = lp
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); isAllCaps = false
        background = rounded(color, 8); setPadding(dp(8), dp(8), dp(8), dp(8))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = dp(8); layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun status() = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0) }

    private fun set(tv: TextView, color: Int, msg: String) { tv.setTextColor(color); tv.text = msg }

    // ---------- connection ----------
    private fun saveConnection() {
        Settings.setConnection(this, urlField.text.toString(), tokenField.text.toString())
        set(connStatus, ACCENT, "Connection saved. Restart Android Auto.")
    }

    private fun testConnection() {
        val url = urlField.text.toString().trim().trimEnd('/'); val token = tokenField.text.toString().trim()
        if (url.isBlank() || token.isBlank()) { set(connStatus, RED, "Enter URL and token."); return }
        set(connStatus, MUTED, "Connecting to $url ...")
        var got = false
        val client = HaClient(url, token, onStates = { map ->
            got = true; handler.post { set(connStatus, ACCENT, "Connected — ${map.size} entities in HA.") }
        }, onChange = { _, _, _ -> })
        client.connect()
        handler.postDelayed({ if (!got) set(connStatus, RED, "Not connected — check URL / token / network.") }, 8000)
    }

    // ---------- layout ----------
    private fun saveLayout() {
        try { configFile().writeText(editor.text.toString()); set(layoutStatus, ACCENT, "Layout saved. Restart Android Auto.") }
        catch (e: Exception) { set(layoutStatus, RED, "Save error: " + e.message) }
    }

    private fun resetLayout() {
        try { if (configFile().exists()) configFile().delete() } catch (e: Exception) {}
        editor.setText(layoutText()); set(layoutStatus, ACCENT, "Reset to bundled layout.")
    }

    private fun validate() {
        val conf = try { ConfigLoader.loadString(editor.text.toString()) } catch (e: Exception) {
            set(layoutStatus, RED, "YAML error: " + e.message); return
        }
        val tabs = conf.tabs.size
        val ents = ConfigLoader.entitiesOf(conf)
        val url = Settings.url(this).ifBlank { urlField.text.toString().trim().trimEnd('/') }
        val token = Settings.token(this).ifBlank { tokenField.text.toString().trim() }
        if (url.isBlank() || token.isBlank()) { set(layoutStatus, AMBER, "YAML OK — $tabs tabs, ${ents.size} entities. (No connection to verify against.)"); return }
        set(layoutStatus, MUTED, "YAML OK — checking entities ...")
        var got = false
        val client = HaClient(url, token, onStates = { map ->
            got = true
            handler.post {
                val missing = ents.filter { !map.containsKey(it) }
                if (missing.isEmpty()) set(layoutStatus, ACCENT, "OK — $tabs tabs, ${ents.size} entities, all found.")
                else set(layoutStatus, AMBER, "$tabs tabs, ${ents.size} entities. NOT found (${missing.size}):\n" + missing.joinToString("\n") { "  - $it" })
            }
        }, onChange = { _, _, _ -> })
        client.connect()
        handler.postDelayed({ if (!got) set(layoutStatus, RED, "Can't verify — not connected.") }, 8000)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, PICK) } catch (e: Exception) { set(layoutStatus, RED, "No file manager available") }
    }

    private fun exportFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "autohome.yaml") }
        try { startActivityForResult(i, SAVE) } catch (e: Exception) { set(layoutStatus, RED, "No file manager available") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK && res == RESULT_OK) data?.data?.let { uri ->
            try { contentResolver.openInputStream(uri)?.bufferedReader()?.use { editor.setText(it.readText()) }; set(layoutStatus, ACCENT, "File loaded. Tap Save.") }
            catch (e: Exception) { set(layoutStatus, RED, "Read error: " + e.message) }
        } else if (req == SAVE && res == RESULT_OK) data?.data?.let { uri ->
            try { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(editor.text.toString()) }; set(layoutStatus, ACCENT, "Exported.") }
            catch (e: Exception) { set(layoutStatus, RED, "Export error: " + e.message) }
        }
    }
}
