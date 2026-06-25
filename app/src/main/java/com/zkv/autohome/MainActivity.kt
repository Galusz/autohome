package com.zkv.autohome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var result: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val PICK = 1
    private val SAVE = 2

    private fun configFile() = File(getExternalFilesDir(null), "autohome.yaml")

    private fun currentText(): String {
        val f = configFile()
        return if (f.exists()) f.readText()
        else try { assets.open("autohome.yaml").bufferedReader().use { it.readText() } }
        catch (e: Exception) {
            try { assets.open("autohome.example.yaml").bufferedReader().use { it.readText() } } catch (e2: Exception) { "" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(24, 26, 32))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "AutoHome — configuration"; textSize = 20f; setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "File: " + configFile().absolutePath; textSize = 10f; setTextColor(Color.rgb(150, 156, 168))
            setPadding(0, pad / 2, 0, pad / 2)
        })

        editor = EditText(this).apply {
            setText(currentText()); textSize = 12f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP; setHorizontallyScrolling(true)
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(32, 35, 42)); setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (320 * d).toInt()))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad / 2, 0, 0) }
        btns.addView(Button(this).apply { text = "Save"; setOnClickListener { save() } })
        btns.addView(Button(this).apply { text = "Test"; setOnClickListener { test() } })
        btns.addView(Button(this).apply { text = "Reset"; setOnClickListener { reset() } })
        root.addView(btns)
        val btns2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, pad / 2) }
        btns2.addView(Button(this).apply { text = "Load file"; setOnClickListener { pickFile() } })
        btns2.addView(Button(this).apply { text = "Save to file"; setOnClickListener { saveToFile() } })
        root.addView(btns2)

        result = TextView(this).apply { text = ""; textSize = 13f; setTextColor(Color.rgb(54, 201, 141)) }
        root.addView(result)

        setContentView(scroll)
    }

    private fun save() {
        result.setTextColor(Color.rgb(54, 201, 141))
        try {
            configFile().writeText(editor.text.toString())
            result.text = "Saved. Restart Android Auto to reload the config."
        } catch (e: Exception) {
            result.setTextColor(Color.rgb(214, 90, 90)); result.text = "Save error: " + e.message
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, PICK) } catch (e: Exception) { result.text = "No file manager available" }
    }

    private fun saveToFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "autohome.yaml") }
        try { startActivityForResult(i, SAVE) } catch (e: Exception) { result.text = "No file manager available" }
    }

    private fun reset() {
        try { if (configFile().exists()) configFile().delete() } catch (e: Exception) {}
        val assetText = try { assets.open("autohome.yaml").bufferedReader().use { it.readText() } }
        catch (e: Exception) { try { assets.open("autohome.example.yaml").bufferedReader().use { it.readText() } } catch (e2: Exception) { "" } }
        editor.setText(assetText)
        result.setTextColor(Color.rgb(54, 201, 141))
        result.text = "Reset to bundled config. Restart Android Auto to apply."
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK && res == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { editor.setText(it.readText()) }
                    result.setTextColor(Color.rgb(54, 201, 141)); result.text = "File loaded. Tap Save."
                } catch (e: Exception) {
                    result.setTextColor(Color.rgb(214, 90, 90)); result.text = "Read error: " + e.message
                }
            }
        } else if (req == SAVE && res == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(editor.text.toString()) }
                    result.setTextColor(Color.rgb(54, 201, 141)); result.text = "Exported to file."
                } catch (e: Exception) {
                    result.setTextColor(Color.rgb(214, 90, 90)); result.text = "Export error: " + e.message
                }
            }
        }
    }

    private fun test() {
        result.setTextColor(Color.rgb(150, 156, 168))
        val conf = try { ConfigLoader.loadString(editor.text.toString()) } catch (e: Exception) {
            result.setTextColor(Color.rgb(214, 90, 90)); result.text = "YAML error: " + e.message; return
        }
        if (conf.url.isBlank() || conf.token.isBlank()) {
            result.setTextColor(Color.rgb(214, 90, 90)); result.text = "Missing url/token in the homeassistant section"; return
        }
        val ents = ConfigLoader.entitiesOf(conf)
        result.text = "Connecting to ${conf.url} ..."
        var got = false
        val client = HaClient(conf.url, conf.token, onStates = { map ->
            got = true
            handler.post {
                val missing = ents.filter { !map.containsKey(it) }
                val sb = StringBuilder("Connected.  Entities in HA: ${map.size}\n")
                sb.append("Entities in config: ${ents.size}\n")
                if (missing.isEmpty()) {
                    result.setTextColor(Color.rgb(54, 201, 141)); sb.append("All entities found.")
                } else {
                    result.setTextColor(Color.rgb(230, 170, 60))
                    sb.append("NOT found (${missing.size}):\n"); missing.forEach { sb.append("  - $it\n") }
                }
                result.text = sb.toString()
            }
        }, onChange = { _, _, _ -> })
        client.connect()
        handler.postDelayed({ if (!got) { result.setTextColor(Color.rgb(214, 90, 90)); result.text = "Not connected — check URL / token / network." } }, 8000)
    }
}
