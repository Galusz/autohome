package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import org.json.JSONObject
import java.util.Locale

class MusicService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var conf: Conf
    private var ha: HaClient? = null

    private val GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val LIST = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    private val states = HashMap<String, Pair<String, JSONObject>>()
    private val childrenByParent = HashMap<String, List<Item>>()
    private val itemByMedia = HashMap<String, Item>()
    private val scopeByMedia = HashMap<String, List<String>>()
    private val styleByMedia = HashMap<String, String>()
    private val usedEntities = HashSet<String>()
    private val entToParents = HashMap<String, MutableSet<String>>()
    private val history = HashMap<String, IntArray>()
    private val histRange = HashMap<String, Pair<Double, Double>>()
    private val histReqs = HashMap<String, Pair<String, Long>>()
    private val cameras = HashMap<String, Bitmap>()
    private val camEntities = HashSet<String>()
    private val quickMedia = HashSet<String>()
    private val parentOfMedia = HashMap<String, String>()
    private var lastQuickId = ""
    private var lastQuickAt = 0L
    private val camTick = object : Runnable {
        override fun run() { fetchCameras(); handler.postDelayed(this, 5000) }
    }
    private val histTick = object : Runnable {
        override fun run() { fetchHistory(); handler.postDelayed(this, 300000) }
    }
    private val dirty = HashSet<String>()
    private var refreshScheduled = false
    private var showScheduled = false
    private var confError: String? = null
    private val refreshR = Runnable { doRefresh() }

    private var scope: List<String> = listOf()
    private var idx = 0
    private val curMid: String get() = scope.getOrElse(idx) { "" }
    private val curItem: Item? get() = itemByMedia[curMid]

    private var numVal = 0.0
    private var numEditing = false
    private var pushFlash = false
    private val slideSeconds = 10
    private var slideRemaining = 10
    private var slideRunning = false
    private val slideTick = object : Runnable {
        override fun run() {
            if (!slideRunning || scope.isEmpty()) return
            slideRemaining--
            if (slideRemaining <= 0) { idx = (idx + 1) % scope.size; slideRemaining = slideSeconds; showEntity() } else applyState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        conf = try { ConfigLoader.load(this) } catch (e: Exception) { confError = e.message; Conf("", "", listOf(), listOf()) }
        buildIndex()
        ha = HaClient(conf.url, conf.token,
            onStates = { m -> handler.post { states.putAll(m); refreshAll(); scheduleShow() } },
            onChange = { id, st, at -> if (usedEntities.contains(id)) handler.post { states[id] = Pair(st, at); dirty.add(id); scheduleRefresh(); if (curUses(id)) scheduleShow() } })
        ha?.connect()
        fetchHistory()
        handler.postDelayed(histTick, 300000)
        fetchCameras()
        handler.postDelayed(camTick, 5000)
        session = MediaSessionCompat(this, "AutoHome")
        session.setCallback(cb)
        session.isActive = true
        sessionToken = session.sessionToken
    }

    private fun buildIndex() {
        conf.tabs.forEach { tab ->
            val p = "tab:" + tab.id
            styleByMedia[p] = tab.style
            indexList(p, tab.items)
        }
        if (conf.home.isNotEmpty()) indexList("__recent__", conf.home)
    }

    private fun indexList(parent: String, items: List<Item>) {
        childrenByParent[parent] = items
        val leaves = ArrayList<String>()
        items.forEach { it ->
            val mid = stableId(it)
            itemByMedia[mid] = it
            parentOfMedia[mid] = parent
            if ((it.type == "switch" || it.type == "button") && it.quick == true) quickMedia.add(mid)
            if (it.entity != null) { usedEntities.add(it.entity); entToParents.getOrPut(it.entity) { HashSet() }.add(parent) }
            it.lines?.forEach { l -> usedEntities.add(l.entity); entToParents.getOrPut(l.entity) { HashSet() }.add(parent) }
            it.parts?.forEach { pp -> usedEntities.add(pp.entity); entToParents.getOrPut(pp.entity) { HashSet() }.add(parent) }
            if (it.type == "card" && (it.chart == "bars" || it.chart == "line") && it.entity != null) {
                histReqs[histKey(it)] = Pair(it.entity, histMin(it))
            }
            if (it.type == "camera" && it.entity != null) camEntities.add(it.entity)
            if (it.items != null) { styleByMedia[mid] = "list"; indexList(mid, it.items) } else leaves.add(mid)
        }
        leaves.forEach { scopeByMedia[it] = leaves }
    }

    private fun refreshAll() {
        notifyChildrenChanged("root")
        childrenByParent.keys.forEach { notifyChildrenChanged(it) }
    }

    private fun histMin(it: Item) = ((it.hours ?: 24.0) * 60 + (it.minutes ?: 0.0)).toLong().coerceAtLeast(5)
    private fun histKey(it: Item) = (it.entity ?: "") + "#" + histMin(it)
    private fun stableId(it: Item) = (it.entity ?: "") + "#" + (it.title ?: "") + "#" + it.type

    private fun curUses(id: String): Boolean {
        val it = curItem ?: return false
        return it.entity == id || it.lines?.any { l -> l.entity == id } == true || it.parts?.any { p -> p.entity == id } == true
    }

    private fun scheduleShow() {
        if (showScheduled || scope.isEmpty()) return
        showScheduled = true
        handler.postDelayed({ showScheduled = false; if (scope.isNotEmpty() && !slideRunning) showEntity() }, 3000)
    }

    private fun fetchCameras() {
        camEntities.forEach { e -> ha?.getSnapshot(e) { bmp -> handler.post { cameras[e] = bmp; dirty.add(e); scheduleRefresh(); if (curItem?.entity == e) scheduleShow() } } }
    }

    private fun cameraCard(item: Item, size: Int, crop: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val snap = cameras[item.entity]
        if (snap != null && snap.width > 0 && snap.height > 0) {
            if (crop) {
                val side = minOf(snap.width, snap.height)
                val sx = (snap.width - side) / 2; val sy = (snap.height - side) / 2
                c.drawBitmap(snap, Rect(sx, sy, sx + side, sy + side), RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
            } else {
                c.drawColor(Color.rgb(10, 10, 12))
                val scale = minOf(size.toFloat() / snap.width, size.toFloat() / snap.height)
                val dw = snap.width * scale; val dh = snap.height * scale
                val left = (size - dw) / 2f; val top = (size - dh) / 2f
                c.drawBitmap(snap, null, RectF(left, top, left + dw, top + dh), p)
            }
        } else {
            c.drawColor(Color.rgb(28, 30, 36))
            p.color = Color.rgb(90, 96, 108); p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.28f
            c.drawText("📷", size / 2f, size * 0.6f, p)
        }
        return bmp
    }

    private fun fetchHistory() {
        histReqs.forEach { (k, req) ->
            ha?.getHistory(req.first, req.second) { vals ->
                handler.post {
                    val s = normSeries(vals)
                    if (s.isNotEmpty()) {
                        history[k] = s; histRange[k] = Pair(vals.minOrNull() ?: 0.0, vals.maxOrNull() ?: 0.0)
                        dirty.add(req.first); scheduleRefresh()
                        if (curItem?.entity == req.first) scheduleShow()
                    }
                }
            }
        }
    }

    private fun normSeries(values: List<Double>, n: Int = 10): IntArray {
        if (values.isEmpty()) return IntArray(0)
        val pts = DoubleArray(n)
        val per = values.size.toDouble() / n
        for (i in 0 until n) {
            val a = (i * per).toInt(); val b = ((i + 1) * per).toInt().coerceAtMost(values.size)
            val slice = if (b > a) values.subList(a, b) else listOf(values[a.coerceIn(0, values.size - 1)])
            pts[i] = slice.average()
        }
        val mn = pts.minOrNull() ?: 0.0; val mx = pts.maxOrNull() ?: 0.0
        return IntArray(n) { i -> if (mx > mn) (5 + (pts[i] - mn) / (mx - mn) * 95).toInt() else 50 }
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refreshR, 10000)
    }

    private fun doRefresh() {
        refreshScheduled = false
        if (dirty.isEmpty()) return
        val parents = HashSet<String>()
        dirty.forEach { e -> entToParents[e]?.let { parents.addAll(it) } }
        parents.forEach { notifyChildrenChanged(it) }
        dirty.clear()
    }

    private val cb = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { open(mediaId ?: "") }
        override fun onPlay() { startShow() }
        override fun onPause() { stopShow() }
        override fun onStop() { stopShow() }
        override fun onSkipToNext() { if (curItem?.type == "number" && numEditing) { numVal = (numVal + (curItem?.step ?: 1.0)).coerceAtMost(curItem?.max ?: 100.0); showEntity() } }
        override fun onSkipToPrevious() { if (curItem?.type == "number" && numEditing) { numVal = (numVal - (curItem?.step ?: 1.0)).coerceAtLeast(curItem?.min ?: 0.0); showEntity() } }
        override fun onSkipToQueueItem(id: Long) { if (id >= 0 && id < scope.size) { idx = id.toInt(); numEditing = false; showEntity() } }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                "TOGGLE" -> actToggle()
                "PUSH" -> actPush()
                "SET" -> { numEditing = true; showEntity() }
                "OK" -> { actNumber(); numEditing = false; showEntity() }
                "HEAT" -> setMode("heat")
                "COOL" -> setMode("cool")
                "OFFC" -> setMode("off")
            }
        }
    }

    private fun open(mid: String) {
        val m = if (itemByMedia[mid] != null) mid else (itemByMedia.entries.firstOrNull { it.value.entity == mid }?.key ?: mid)
        scope = scopeByMedia[m] ?: listOf(m)
        idx = scope.indexOf(m).coerceAtLeast(0)
        numEditing = false; slideRunning = false
        handler.removeCallbacks(slideTick)
        setQueue(); showEntity()
    }

    private fun startShow() { if (scope.isEmpty()) return; slideRunning = true; slideRemaining = slideSeconds; showEntity(); handler.removeCallbacks(slideTick); handler.postDelayed(slideTick, 1000) }
    private fun stopShow() { slideRunning = false; handler.removeCallbacks(slideTick); showEntity() }

    private fun valStr(e: String?): String = if (e == null) "" else states[e]?.first ?: "—"
    private fun valNum(e: String?): Double? = if (e == null) null else states[e]?.first?.toDoubleOrNull()
    private fun isOn(e: String?): Boolean { val s = valStr(e).lowercase(); return s == "on" || s == "open" || s == "true" || s == "home" }
    private fun pctOf(it: Item): Int { val v = valNum(it.entity) ?: return 0; return (((v - it.min) / (it.max - it.min)) * 100.0).toInt().coerceIn(0, 100) }
    private fun unit(it: Item) = it.unit ?: ""
    private fun numCurrent(it: Item): Double {
        val e = it.entity ?: return it.min
        val s = states[e] ?: return it.min
        return if (e.startsWith("climate.")) s.second.optDouble("temperature", it.min) else s.first.toDoubleOrNull() ?: it.min
    }

    private fun showEntity() {
        val it = curItem ?: return
        if (it.type == "number" && !numEditing) numVal = numCurrent(it)
        val dur = if (slideRunning) slideSeconds * 1000L else -1L
        val ctr = if (scope.size > 1) "   (" + (idx + 1) + "/" + scope.size + ")" else ""
        val mb = MediaMetadataCompat.Builder().putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, it.title ?: it.entity ?: "")
        when (it.type) {
            "switch" -> mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, (if (isOn(it.entity)) "WLACZONE" else "WYLACZONE") + ctr)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, switchArt(480, isOn(it.entity)))
            "button" -> mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, (if (it.entity?.startsWith("cover.") == true) valStr(it.entity) else "") + ctr)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, buttonArt(480, false, pushFlash))
            "number" -> {
                val art = if (isClimate(it)) climateArt(numVal, it.min, it.max, valStr(it.entity)) else numberArt(numVal, it.min, it.max, unit(it))
                val sub = if (numEditing) "Strzalki +-, OK zapisuje" else if (isClimate(it)) modeLabel(valStr(it.entity)) + "   " + fmt(numVal) + unit(it) + "  -  SET" else fmt(numVal) + " " + unit(it) + "  -  SET"
                mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub + ctr).putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            }
            "text" -> {
                val rows = (it.lines ?: listOf()).map { l -> Pair(l.label.replace("\n", " ").trim(), valStr(l.entity)) }
                mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, rows.joinToString("   ") { r -> r.first + " " + r.second })
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, textPanel(560, rows)) }
            "progress", "battery" -> { val pc = pctOf(it); val b = it.bar
                val art = if (it.type == "battery") batteryIcon(480, pc, false) else vbar(480, pc, grad(it))
                mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "$pc%  " + textBar(pc.toDouble(), b?.width ?: 14, b?.fill ?: "█", b?.empty ?: "░") + ctr).putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) }
            "card" -> mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, (if (it.entity != null) valStr(it.entity) + unit(it) else "") + ctr)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cardIcon(it))
            "camera" -> mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ctr.trim())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cameraCard(it, 720, crop = false))
            else -> mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, valStr(it.entity) + " " + unit(it) + ctr)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, ring(480, pctOf(it)))
        }
        session.setMetadata(mb.build())
        applyState()
    }

    private fun applyState() {
        val it = curItem
        val b = PlaybackStateCompat.Builder()
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
        when (it?.type) {
            "switch" -> b.addCustomAction("TOGGLE", if (isOn(it.entity)) "Wylacz" else "Wlacz", if (isOn(it.entity)) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off)
            "button" -> b.addCustomAction("PUSH", "Nacisnij", R.drawable.ic_push)
            "number" -> if (numEditing) { actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT; b.addCustomAction("OK", "Zapisz", R.drawable.ic_check) } else {
                b.addCustomAction("SET", "Ustaw", R.drawable.ic_edit)
                if (isClimate(it)) { b.addCustomAction("HEAT", "Grzanie", R.drawable.ic_fire); b.addCustomAction("COOL", "Chlodzenie", R.drawable.ic_snow); b.addCustomAction("OFFC", "Wylacz", R.drawable.ic_power) }
            }
        }
        b.setActions(actions)
        b.setActiveQueueItemId(idx.toLong())
        val pos = if (slideRunning) slideRemaining * 1000L else 0L
        b.setState(if (slideRunning) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, pos, 0f)
        session.setPlaybackState(b.build())
    }

    private fun actToggle() { val it = curItem ?: return; val e = it.entity ?: return; val d = e.substringBefore("."); ha?.callService(d, if (isOn(e)) "turn_off" else "turn_on", e) }
    private fun actPush() {
        val it = curItem ?: return; val e = it.entity ?: return
        val svc = it.service ?: when (e.substringBefore(".")) { "button" -> "button.press"; "scene", "script" -> e.substringBefore(".") + ".turn_on"; "cover" -> "cover.toggle"; else -> "homeassistant.toggle" }
        ha?.callService(svc.substringBefore("."), svc.substringAfter("."), e)
        pushFlash = true; showEntity(); handler.postDelayed({ pushFlash = false; showEntity() }, 700)
    }
    private fun actNumber() {
        val it = curItem ?: return; val e = it.entity ?: return
        if (e.startsWith("climate.")) ha?.callService("climate", "set_temperature", e, JSONObject().put("temperature", numVal))
        else ha?.callService(e.substringBefore("."), "set_value", e, JSONObject().put("value", numVal))
    }

    // ---------- queue ----------
    private fun setQueue() {
        val q = scope.mapIndexed { i, mid ->
            val it = itemByMedia[mid]
            val d = MediaDescriptionCompat.Builder().setMediaId(mid).setTitle(it?.title ?: mid).setIconBitmap(icon(it, 120)).build()
            MediaSessionCompat.QueueItem(d, i.toLong())
        }
        session.setQueue(q); session.setQueueTitle("Encje")
    }

    // ---------- browse ----------
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val forYou = rootHints?.getBoolean(BrowserRoot.EXTRA_SUGGESTED, false) == true ||
                rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT, false) == true
        if (conf.home.isNotEmpty() && forYou) {
            val rex = Bundle().apply {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, LIST)
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
            }
            return BrowserRoot("__recent__", rex)
        }
        val ex = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, LIST)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
        }
        return BrowserRoot("root", ex)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val itm = itemByMedia[parentId]
        if (itm != null && quickMedia.contains(parentId)) {
            val now = SystemClock.elapsedRealtime()
            if (!(parentId == lastQuickId && now - lastQuickAt < 800)) {
                if (itm.type == "switch") toggleEntity(itm) else pushEntity(itm)
            }
            lastQuickId = parentId; lastQuickAt = now
            result.sendResult(buildNodes(parentOfMedia[parentId] ?: "root"))
            return
        }
        result.sendResult(buildNodes(parentId))
    }

    private fun buildNodes(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val out = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (parentId == "root") {
            if (conf.tabs.isEmpty()) {
                val d = MediaDescriptionCompat.Builder().setMediaId("err").setTitle("Config error (YAML)").setSubtitle(confError ?: "check autohome.yaml").setIconBitmap(listIcon(150)).build()
                out.add(MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            } else conf.tabs.forEach { out.add(tabNode(it)) }
        } else {
            val items = childrenByParent[parentId]
            if (items != null) items.forEach { out.add(node(stableId(it), it)) }
        }
        return out
    }

    private fun toggleEntity(it: Item) {
        val e = it.entity ?: return
        val on = isOn(e)
        ha?.callService(e.substringBefore("."), if (on) "turn_off" else "turn_on", e)
        states[e] = Pair(if (on) "off" else "on", states[e]?.second ?: JSONObject())
    }

    private fun pushEntity(it: Item) {
        val e = it.entity ?: return
        val svc = it.service ?: when (e.substringBefore(".")) { "button" -> "button.press"; "scene", "script" -> e.substringBefore(".") + ".turn_on"; "cover" -> "cover.toggle"; else -> "homeassistant.toggle" }
        ha?.callService(svc.substringBefore("."), svc.substringAfter("."), e)
    }

    private fun tabNode(tab: Tab): MediaBrowserCompat.MediaItem {
        val st = if (tab.style == "grid") GRID else LIST
        val ex = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, st)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, st)
        }
        val d = MediaDescriptionCompat.Builder().setMediaId("tab:" + tab.id).setTitle(tab.title).setExtras(ex).build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun node(mid: String, it: Item): MediaBrowserCompat.MediaItem {
        val ex = Bundle()
        if (it.group != null) ex.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it.group)
        if (it.type == "gauge" || it.type == "battery" || it.type == "progress") ex.putDouble(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, pctOf(it) / 100.0)
        if (it.items != null) { val st = if (it.style == "grid") GRID else LIST; ex.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, st); ex.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, st) }
        val title = when {
            it.type == "card" -> " "
            it.type == "text" -> { val sp = it.sep ?: "    "; (it.title ?: "") + sp + (it.lines ?: listOf()).joinToString(sp) { l -> l.label.replace("\n", " ").trim() + " " + valStr(l.entity) } }
            else -> it.title ?: it.entity ?: ""
        }
        val sub = if (it.type == "card" || it.type == "camera" || it.type == "text") "" else browseSub(it)
        val d = MediaDescriptionCompat.Builder().setMediaId(mid).setTitle(title)
            .setSubtitle(sub).setIconBitmap(icon(it, 150)).setExtras(ex).build()
        val flag = if (it.items != null || quickMedia.contains(mid)) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        return MediaBrowserCompat.MediaItem(d, flag)
    }

    private fun browseSub(it: Item): String = when (it.type) {
        "switch" -> if (isOn(it.entity)) "ON" else "OFF"
        "button" -> if (it.entity?.startsWith("cover.") == true) valStr(it.entity) else ""
        "room" -> (it.items?.size ?: 0).toString() + " urzadzen"
        "number" -> if (isClimate(it)) modeLabel(valStr(it.entity)) + " " + fmt(numCurrent(it)) + unit(it) else fmt(numCurrent(it)) + " " + unit(it)
        "progress", "battery" -> { val pc = pctOf(it); val b = it.bar; "$pc%  " + textBar(pc.toDouble(), b?.width ?: 14, b?.fill ?: "█", b?.empty ?: "░") }
        "text" -> (it.lines ?: listOf()).joinToString("   ") { l -> l.label.replace("\n", " ").trim() + " " + valStr(l.entity) }
        else -> valStr(it.entity) + " " + unit(it)
    }

    // ---------- icons ----------
    private fun icon(it: Item?, size: Int): Bitmap {
        if (it == null) return ring(size, 50)
        if (it.type == "camera") return cameraCard(it, 512)
        val ic = it.icon
        return when {
            ic == "toggle" -> switchArt(size, isOn(it.entity))
            ic == "battery" -> batteryIcon(size, pctOf(it), false)
            ic == "progress" -> vbar(size, pctOf(it), grad(it))
            ic == "gauge" || ic == "ring" -> ring(size, pctOf(it), false)
            ic == "list" -> listIcon(size)
            ic != null && ic.length == 1 && ic[0].isLetter() -> roomIcon(ic, parseCol(it.color))
            ic != null && ic.isNotBlank() -> glyph(size, ic)
            else -> when (it.type) {
                "switch" -> switchArt(size, isOn(it.entity))
                "button" -> buttonArt(size, false, false)
                "battery" -> batteryIcon(size, pctOf(it), false)
                "progress" -> vbar(size, pctOf(it), grad(it))
                "text" -> listIcon(size)
                "room" -> roomIcon((it.title ?: "?").take(1).uppercase(), parseCol(it.color))
                "card" -> cardIcon(it)
                else -> ring(size, pctOf(it), false)
            }
        }
    }

    private fun parseCol(s: String?): Int = try { Color.parseColor(s ?: "#568CFF") } catch (e: Exception) { Color.rgb(86, 140, 255) }

    private fun glyph(size: Int, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.62f; p.color = Color.WHITE
        c.drawText(text, size / 2f, size * 0.7f, p)
        return bmp
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun textBar(pct: Double, width: Int, fill: String, empty: String): String {
        if (fill != "█") { val f = Math.round(width * pct / 100.0).toInt().coerceIn(0, width); return fill.repeat(f) + empty.repeat(width - f) }
        val total = (width * pct / 100.0).coerceIn(0.0, width.toDouble())
        val full = total.toInt()
        val parts = arrayOf("", "▏", "▎", "▍", "▌", "▋", "▊", "▉")
        val frac = ((total - full) * 8).toInt().coerceIn(0, 7)
        val sb = StringBuilder("█".repeat(full))
        var used = full
        if (full < width && frac > 0) { sb.append(parts[frac]); used++ }
        sb.append(empty.repeat((width - used).coerceAtLeast(0)))
        return sb.toString()
    }

    private fun ring(size: Int, percent: Int, label: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = if (percent < 25) Color.rgb(214, 90, 90) else Color.rgb(54, 201, 141)
        c.drawArc(rr, 135f, 270f * percent / 100f, false, p)
        if (label && size >= 280) { p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.24f; c.drawText("$percent%", size / 2f, size / 2f + size * 0.08f, p) }
        return bmp
    }

    private fun switchArt(size: Int, on: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tw = size * 0.62f; val th = size * 0.30f; val left = (size - tw) / 2f; val top = size * 0.28f
        p.color = if (on) Color.rgb(54, 201, 141) else Color.rgb(70, 74, 84)
        c.drawRoundRect(RectF(left, top, left + tw, top + th), th / 2f, th / 2f, p)
        val r = th / 2f - size * 0.025f; val ky = top + th / 2f; val kx = if (on) left + tw - th / 2f else left + th / 2f
        p.color = Color.WHITE; c.drawCircle(kx, ky, r, p)
        if (size >= 280) { p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.13f; p.color = if (on) Color.rgb(54, 201, 141) else Color.rgb(150, 156, 168); c.drawText(if (on) "ON" else "OFF", size / 2f, top + th + size * 0.18f, p) }
        return bmp
    }

    private fun buttonArt(size: Int, open: Boolean, flash: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f; val cy = size / 2f; val r = size * 0.26f
        val base = if (flash) Color.rgb(255, 200, 80) else Color.rgb(150, 156, 168)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.05f; p.color = base
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.42f, p)
        return bmp
    }

    private fun batteryIcon(size: Int, pct: Int, charging: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val l = size * 0.12f; val t = size * 0.32f; val r = size * 0.80f; val bo = size * 0.68f
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.045f; p.color = Color.rgb(150, 156, 168)
        c.drawRoundRect(RectF(l, t, r, bo), size * 0.06f, size * 0.06f, p)
        p.style = Paint.Style.FILL; c.drawRoundRect(RectF(r, size * 0.43f, r + size * 0.06f, size * 0.57f), size * 0.02f, size * 0.02f, p)
        val m = size * 0.05f; val iw = (r - m) - (l + m)
        p.color = if (pct < 20) Color.rgb(214, 90, 90) else if (charging) Color.rgb(54, 201, 141) else Color.rgb(80, 160, 240)
        c.drawRoundRect(RectF(l + m, t + m, l + m + iw * pct / 100f, bo - m), size * 0.03f, size * 0.03f, p)
        return bmp
    }

    private fun grad(item: Item): IntArray {
        val g = item.grad?.mapNotNull { runCatching { Color.parseColor(it) }.getOrNull() }
        return when {
            g != null && g.size >= 2 -> g.toIntArray()
            g != null && g.size == 1 -> intArrayOf(g[0], g[0])
            item.color != null -> { val c = parseCol(item.color); intArrayOf(c, c) }
            else -> intArrayOf(Color.rgb(214, 90, 90), Color.rgb(224, 180, 60), Color.rgb(54, 201, 141))
        }
    }

    private fun vbar(size: Int, pct: Int, cols: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = size * 0.34f; val left = (size - w) / 2f; val top = size * 0.12f; val bot = size * 0.88f
        val rad = w / 2f
        p.color = Color.rgb(45, 48, 56); c.drawRoundRect(RectF(left, top, left + w, bot), rad, rad, p)
        val fillTop = bot - (bot - top) * pct / 100f
        p.shader = LinearGradient(0f, bot, 0f, top, cols, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(left, fillTop, left + w, bot), rad, rad, p)
        p.shader = null
        return bmp
    }

    private fun bars(size: Int, h: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = Color.rgb(86, 140, 255)
        val n = h.size; val gap = size * 0.03f; val bw = (size - gap * (n + 1)) / n
        for (i in 0 until n) {
            val bh = size * 0.7f * h[i] / 100f; val x = gap + i * (bw + gap)
            c.drawRoundRect(RectF(x, size - size * 0.15f - bh, x + bw, size - size * 0.15f), 6f, 6f, p)
        }
        return bmp
    }

    private fun line(size: Int, v: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.035f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = Color.rgb(54, 201, 141)
        val path = Path(); val top = size * 0.18f; val bot = size * 0.82f; val left = size * 0.1f; val right = size * 0.9f
        for (i in v.indices) {
            val x = left + (right - left) * i / (v.size - 1); val y = bot - (bot - top) * v[i] / 100f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p); return bmp
    }

    private fun cardIcon(it: Item): Bitmap = when (it.chart) {
        "pie" -> pieCard(it)
        "ribbon" -> ribbonCard(it)
        "bars" -> chartCard(it, bars(240, history[histKey(it)] ?: intArrayOf(20, 45, 70, 55, 90, 60)))
        "line" -> chartCard(it, line(240, history[histKey(it)] ?: intArrayOf(40, 55, 50, 65, 72, 68, 80)))
        else -> ringCard(it)
    }

    private fun fmtNum(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun cardHeader(c: Canvas, p: Paint, s: Int, title: String, value: String) {
        p.style = Paint.Style.FILL
        p.color = Color.rgb(165, 172, 185); p.textAlign = Paint.Align.LEFT; p.textSize = s * 0.08f
        c.drawText(title, s * 0.07f, s * 0.135f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.RIGHT; p.textSize = s * 0.095f
        c.drawText(value, s * 0.93f, s * 0.135f, p)
    }

    private fun ringCard(item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(165, 172, 185); p.textAlign = Paint.Align.LEFT; p.textSize = s * 0.08f
        c.drawText(item.title ?: "", s * 0.07f, s * 0.135f, p)
        val pc = pctOf(item)
        val cx = s / 2f; val cy = s * 0.58f; val rOut = s * 0.31f
        val rr = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.14f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = if (pc < 25) Color.rgb(214, 90, 90) else Color.rgb(54, 201, 141); c.drawArc(rr, 135f, 270f * pc / 100f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.095f
        c.drawText(valStr(item.entity) + unit(item), cx, cy + s * 0.035f, p)
        p.color = Color.rgb(120, 126, 138); p.textSize = s * 0.056f
        p.textAlign = Paint.Align.LEFT; c.drawText(fmtNum(item.min), s * 0.1f, s * 0.965f, p)
        p.textAlign = Paint.Align.RIGHT; c.drawText(fmtNum(item.max), s * 0.9f, s * 0.965f, p)
        return bmp
    }

    private fun chartCard(item: Item, chart: Bitmap): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cardHeader(c, p, s, item.title ?: "", valStr(item.entity) + unit(item))
        val cs = s * 0.86f; val dst = RectF((s - cs) / 2f, s * 0.22f, (s + cs) / 2f, s * 0.22f + cs * 0.84f)
        c.drawBitmap(chart, null, dst, null)
        histRange[histKey(item)]?.let { rg ->
            p.color = Color.rgb(120, 126, 138); p.textSize = s * 0.056f; p.textAlign = Paint.Align.RIGHT
            c.drawText(fmtNum(rg.second), s * 0.95f, s * 0.3f, p)
            c.drawText(fmtNum(rg.first), s * 0.95f, s * 0.95f, p)
        }
        return bmp
    }

    private fun partVals(item: Item): List<Triple<String?, Double, Int>> =
        item.parts?.map { Triple(it.label, valNum(it.entity) ?: 0.0, parseCol(it.color)) } ?: listOf()

    private fun pieCard(item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(165, 172, 185); p.textAlign = Paint.Align.LEFT; p.textSize = s * 0.085f
        c.drawText(item.title ?: "", s * 0.1f, s * 0.15f, p)
        val parts = partVals(item)
        val sum = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val cx = s / 2f; val cy = s * 0.58f; val rOut = s * 0.3f
        val rr = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.14f
        var start = -90f
        for (t in parts) {
            val sweep = (360.0 * t.second / sum).toFloat()
            p.color = t.third; c.drawArc(rr, start, sweep - 2f, false, p); start += sweep
        }
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.095f
        c.drawText(fmtNum(sum) + unit(item), cx, cy + s * 0.035f, p)
        return bmp
    }

    private fun ribbonCard(item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(165, 172, 185); p.textAlign = Paint.Align.LEFT; p.textSize = s * 0.085f
        c.drawText(item.title ?: "", s * 0.1f, s * 0.14f, p)
        val parts = partVals(item)
        val sum = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val n = parts.size.coerceAtLeast(1)
        val top = s * 0.24f; val rowH = (s * 0.66f) / n
        val barLeft = s * 0.1f; val barMax = s * 0.6f
        for (i in parts.indices) {
            val t = parts[i]; val y = top + rowH * i + rowH * 0.5f; val frac = (t.second / sum).toFloat()
            val bh = rowH * 0.26f
            p.color = Color.rgb(45, 48, 56); c.drawRoundRect(RectF(barLeft, y - bh, barLeft + barMax, y + bh), bh, bh, p)
            p.color = t.third; c.drawRoundRect(RectF(barLeft, y - bh, barLeft + barMax * frac, y + bh), bh, bh, p)
            if (t.first != null) { p.color = Color.WHITE; p.textAlign = Paint.Align.LEFT; p.textSize = rowH * 0.3f; c.drawText(t.first!!, barLeft + bh, y + rowH * 0.1f, p) }
            p.color = Color.WHITE; p.textAlign = Paint.Align.RIGHT; p.textSize = rowH * 0.32f
            c.drawText("${(frac * 100).toInt()}%", s * 0.93f, y + rowH * 0.11f, p)
        }
        return bmp
    }

    private fun textPanel(size: Int, rows: List<Pair<String, String>>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = rows.size.coerceAtLeast(1)
        val pad = size * 0.06f
        val lh = (size - 2 * pad) / n
        val ts = (lh * 0.46f).coerceAtMost(size * 0.085f)
        for (i in rows.indices) {
            val y = pad + lh * (i + 0.66f)
            if (i > 0) { p.color = Color.rgb(40, 43, 50); p.strokeWidth = 1f; c.drawLine(pad, pad + lh * i, size - pad, pad + lh * i, p) }
            p.style = Paint.Style.FILL; p.textSize = ts
            p.textAlign = Paint.Align.LEFT; p.color = Color.rgb(180, 186, 198)
            c.drawText(rows[i].first, pad, y, p)
            p.textAlign = Paint.Align.RIGHT; p.color = Color.WHITE
            c.drawText(rows[i].second, size - pad, y, p)
        }
        return bmp
    }

    private fun listIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(150, 156, 168); p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = size * 0.07f
        for (i in 0..2) { val y = size * (0.32f + i * 0.18f); c.drawLine(size * 0.28f, y, size * 0.72f, y, p) }
        return bmp
    }

    private fun isClimate(it: Item?) = it?.entity?.startsWith("climate.") == true

    private fun setMode(m: String) {
        val e = curItem?.entity ?: return
        ha?.callService("climate", "set_hvac_mode", e, JSONObject().put("hvac_mode", m))
        states[e] = Pair(m, states[e]?.second ?: JSONObject())
        showEntity()
    }

    private fun modeColor(s: String): Int = when (s) {
        "heat" -> Color.rgb(255, 120, 60); "cool" -> Color.rgb(80, 160, 240)
        "off" -> Color.rgb(110, 114, 124); "heat_cool", "auto" -> Color.rgb(54, 201, 141)
        else -> Color.rgb(150, 156, 168)
    }

    private fun modeLabel(s: String): String = when (s) {
        "heat" -> "GRZANIE"; "cool" -> "CHLODZENIE"; "off" -> "WYLACZONA"
        "heat_cool", "auto" -> "AUTO"; "dry" -> "OSUSZANIE"; "fan_only" -> "WENTYLACJA"
        else -> s.uppercase()
    }

    private fun climateArt(v: Double, vmin: Double, vmax: Double, mode: String): Bitmap {
        val size = 480; val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.rgb(18, 18, 22)); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pct = (((v - vmin) / (vmax - vmin)) * 100.0).toFloat()
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        val col = if (numEditing) Color.rgb(86, 140, 255) else modeColor(mode)
        p.color = col; c.drawArc(rr, 135f, 270f * pct / 100f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.21f
        c.drawText(fmt(v) + "°", size / 2f, size / 2f + size * 0.02f, p)
        p.color = col; p.textSize = size * 0.08f
        c.drawText(if (numEditing) "EDYCJA" else modeLabel(mode), size / 2f, size * 0.66f, p)
        return bmp
    }

    private fun roomIcon(letter: String, col: Int): Bitmap {
        val s = 160; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = col; c.drawRoundRect(RectF(8f, 8f, s - 8f, s - 8f), 28f, 28f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.5f
        c.drawText(letter, s / 2f, s / 2f + s * 0.18f, p); return bmp
    }

    private fun numberArt(v: Double, vmin: Double, vmax: Double, u: String): Bitmap {
        val size = 480; val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.rgb(18, 18, 22)); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pct = (((v - vmin) / (vmax - vmin)) * 100.0).toFloat()
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = if (numEditing) Color.rgb(86, 140, 255) else Color.rgb(54, 201, 141)
        c.drawArc(rr, 135f, 270f * pct / 100f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.21f
        c.drawText(fmt(v), size / 2f, size / 2f + size * 0.02f, p)
        p.color = Color.rgb(150, 156, 168); p.textSize = size * 0.085f; c.drawText(u, size / 2f, size * 0.64f, p)
        if (numEditing) { p.textSize = size * 0.075f; p.color = Color.rgb(86, 140, 255); c.drawText("EDYCJA", size / 2f, size * 0.86f, p) }
        return bmp
    }

    override fun onDestroy() { slideRunning = false; session.release(); super.onDestroy() }
}
