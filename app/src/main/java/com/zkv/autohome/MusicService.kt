package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import org.json.JSONObject

class MusicService : MediaBrowserServiceCompat(), RenderCtx {
    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var conf: Conf
    private var haClient: HaClient? = null
    override val ha: HaClient? get() = haClient

    private val GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val LIST = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    private val states = HashMap<String, Pair<String, JSONObject>>()
    private val childrenByParent = HashMap<String, List<Item>>()
    private val itemByMedia = HashMap<String, Item>()
    private val scopeByMedia = HashMap<String, List<String>>()
    private val usedEntities = HashSet<String>()
    private val entToParents = HashMap<String, MutableSet<String>>()
    private val historyMap = HashMap<String, IntArray>()
    private val histRangeMap = HashMap<String, Pair<Double, Double>>()
    private val histReqs = HashMap<String, Pair<String, Long>>()
    private val camerasMap = HashMap<String, Bitmap>()
    private val camEntities = HashSet<String>()
    private val dirty = HashSet<String>()
    private var refreshScheduled = false
    private var showScheduled = false
    private var confError: String? = null

    private var scope: List<String> = listOf()
    private var idx = 0
    private val ix = Interaction()
    private val curMid: String get() = scope.getOrElse(idx) { "" }
    private val curItem: Item? get() = itemByMedia[curMid]

    private val slideSeconds = 10
    private var slideRemaining = 10
    private var slideRunning = false
    private val slideTick = object : Runnable {
        override fun run() {
            if (!slideRunning || scope.isEmpty()) return
            slideRemaining--
            if (slideRemaining <= 0) { idx = (idx + 1) % scope.size; slideRemaining = slideSeconds; ix.editing = false; showEntity() } else applyState()
            handler.postDelayed(this, 1000)
        }
    }
    private val camTick = object : Runnable { override fun run() { fetchCameras(); handler.postDelayed(this, 5000) } }
    private val histTick = object : Runnable { override fun run() { fetchHistory(); handler.postDelayed(this, 300000) } }
    private val refreshR = Runnable { doRefresh() }

    override fun onCreate() {
        super.onCreate()
        conf = try { ConfigLoader.load(this) } catch (e: Exception) { confError = e.message; Conf("", "", listOf(), listOf()) }
        buildIndex()
        haClient = HaClient(conf.url, conf.token,
            onStates = { m -> handler.post { states.putAll(m); refreshAll(); scheduleShow() } },
            onChange = { id, st, at -> if (usedEntities.contains(id)) handler.post { states[id] = Pair(st, at); dirty.add(id); scheduleRefresh(); if (curUses(id)) scheduleShow() } })
        haClient?.connect()
        fetchHistory(); handler.postDelayed(histTick, 300000)
        fetchCameras(); handler.postDelayed(camTick, 5000)
        session = MediaSessionCompat(this, "AutoHome")
        session.setCallback(cb)
        session.isActive = true
        sessionToken = session.sessionToken
    }

    // ---------- RenderCtx ----------
    override fun valStr(e: String?): String = if (e == null) "" else states[e]?.first ?: "—"
    override fun valNum(e: String?): Double? = if (e == null) null else states[e]?.first?.toDoubleOrNull()
    override fun isOn(e: String?): Boolean { val s = valStr(e).lowercase(); return s == "on" || s == "open" || s == "true" || s == "home" }
    override fun attr(e: String?): JSONObject? = if (e == null) null else states[e]?.second
    override fun camera(e: String?): Bitmap? = if (e == null) null else camerasMap[e]
    override fun history(it: Item): IntArray? = historyMap[histKey(it)]
    override fun range(it: Item): Pair<Double, Double>? = histRangeMap[histKey(it)]
    override fun setState(e: String, state: String) { states[e] = Pair(state, states[e]?.second ?: JSONObject()) }
    override fun setAttrInt(e: String, key: String, value: Int) { val a = states[e]?.second ?: JSONObject(); a.put(key, value); states[e] = Pair(states[e]?.first ?: "on", a) }
    override fun str(id: Int): String = getString(id)
    override fun renderRes(resId: Int, size: Int, tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        androidx.core.content.ContextCompat.getDrawable(this, resId)?.mutate()?.let { d ->
            d.setTint(tint)
            val pad = (size * 0.16f).toInt(); d.setBounds(pad, pad, size - pad, size - pad); d.draw(Canvas(bmp))
        }
        return bmp
    }

    // ---------- index ----------
    private fun buildIndex() { conf.tabs.forEach { tab -> indexList("tab:" + tab.id, tab.items) } }
    private fun indexList(parent: String, items: List<Item>) {
        childrenByParent[parent] = items
        val leaves = ArrayList<String>()
        items.forEach { it ->
            val mid = stableId(it)
            itemByMedia[mid] = it
            if (it.entity != null) { usedEntities.add(it.entity); entToParents.getOrPut(it.entity) { HashSet() }.add(parent) }
            it.lines?.forEach { l -> usedEntities.add(l.entity); entToParents.getOrPut(l.entity) { HashSet() }.add(parent) }
            it.parts?.forEach { pp -> usedEntities.add(pp.entity); entToParents.getOrPut(pp.entity) { HashSet() }.add(parent) }
            if (it.type in PANEL_TYPES) it.items?.forEach { s -> s.entity?.let { e -> usedEntities.add(e); entToParents.getOrPut(e) { HashSet() }.add(parent) } }
            if (it.type == "card" && (it.chart == "bars" || it.chart == "line") && it.entity != null) histReqs[histKey(it)] = Pair(it.entity, histMin(it))
            if (it.type == "camera" && it.entity != null) camEntities.add(it.entity)
            if (it.items != null && it.type !in PANEL_TYPES) indexList(mid, it.items) else leaves.add(mid)
        }
        leaves.forEach { scopeByMedia[it] = leaves }
    }
    private fun histMin(it: Item) = ((it.hours ?: 24.0) * 60 + (it.minutes ?: 0.0)).toLong().coerceAtLeast(5)
    private fun histKey(it: Item) = (it.entity ?: "") + "#" + histMin(it)
    private fun stableId(it: Item) = (it.entity ?: "") + "#" + (it.title ?: "") + "#" + it.type
    private fun curUses(id: String): Boolean { val it = curItem ?: return false; return it.entity == id || it.lines?.any { l -> l.entity == id } == true || it.parts?.any { p -> p.entity == id } == true || it.items?.any { s -> s.entity == id } == true }

    // ---------- refresh ----------
    private fun refreshAll() { notifyChildrenChanged("root"); childrenByParent.keys.forEach { notifyChildrenChanged(it) } }
    private fun scheduleRefresh() { if (refreshScheduled) return; refreshScheduled = true; handler.postDelayed(refreshR, 10000) }
    private fun doRefresh() { refreshScheduled = false; if (dirty.isEmpty()) return; val parents = HashSet<String>(); dirty.forEach { e -> entToParents[e]?.let { parents.addAll(it) } }; parents.forEach { notifyChildrenChanged(it) }; dirty.clear() }
    private fun refreshItemParents(item: Item) {
        val ents = HashSet<String>()
        item.entity?.let { ents.add(it) }
        item.items?.forEach { s -> s.entity?.let { ents.add(it) } }
        val parents = HashSet<String>()
        ents.forEach { e -> entToParents[e]?.let { parents.addAll(it) } }
        parents.forEach { notifyChildrenChanged(it) }
    }
    private fun scheduleShow() { if (showScheduled || scope.isEmpty()) return; showScheduled = true; handler.postDelayed({ showScheduled = false; if (scope.isNotEmpty() && !slideRunning) showEntity() }, 3000) }

    private fun fetchCameras() { camEntities.forEach { e -> ha?.getSnapshot(e) { bmp -> handler.post { camerasMap[e] = bmp; dirty.add(e); scheduleRefresh(); if (curItem?.entity == e) scheduleShow() } } } }
    private fun fetchHistory() {
        histReqs.forEach { (k, req) -> ha?.getHistory(req.first, req.second) { vals -> handler.post {
            val s = normSeries(vals)
            if (s.isNotEmpty()) { historyMap[k] = s; histRangeMap[k] = Pair(vals.minOrNull() ?: 0.0, vals.maxOrNull() ?: 0.0); dirty.add(req.first); scheduleRefresh(); if (curItem?.entity == req.first) scheduleShow() }
        } } }
    }
    private fun normSeries(values: List<Double>, n: Int = 80): IntArray {
        if (values.isEmpty()) return IntArray(0)
        val pts = DoubleArray(n); val per = values.size.toDouble() / n
        for (i in 0 until n) { val a = (i * per).toInt(); val b = ((i + 1) * per).toInt().coerceAtMost(values.size); val slice = if (b > a) values.subList(a, b) else listOf(values[a.coerceIn(0, values.size - 1)]); pts[i] = slice.average() }
        val mn = pts.minOrNull() ?: 0.0; val mx = pts.maxOrNull() ?: 0.0
        return IntArray(n) { i -> if (mx > mn) (5 + (pts[i] - mn) / (mx - mn) * 95).toInt() else 50 }
    }

    // ---------- session callbacks ----------
    private val cb = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { open(mediaId ?: "") }
        override fun onPlay() { startShow() }
        override fun onPause() { stopShow() }
        override fun onStop() { stopShow() }
        override fun onSkipToNext() { val it = curItem; if (it?.type == "number" && ix.editing) { ix.editVal = (ix.editVal + it.step).coerceAtMost(it.max); showEntity() } }
        override fun onSkipToPrevious() { val it = curItem; if (it?.type == "number" && ix.editing) { ix.editVal = (ix.editVal - it.step).coerceAtLeast(it.min); showEntity() } }
        override fun onSkipToQueueItem(id: Long) { if (id >= 0 && id < scope.size) { idx = id.toInt(); ix.editing = false; showEntity() } }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            val it = curItem ?: return; val a = action ?: return
            Widgets.of(it.type).onAction(this@MusicService, it, ix, a)
            showEntity()
            refreshItemParents(it)
            if (ix.flash) handler.postDelayed({ ix.flash = false; ix.flashIdx = -1; showEntity() }, 700)
        }
    }

    private fun open(mid: String) {
        val m = if (itemByMedia[mid] != null) mid else (itemByMedia.entries.firstOrNull { it.value.entity == mid }?.key ?: mid)
        scope = scopeByMedia[m] ?: listOf(m)
        idx = scope.indexOf(m).coerceAtLeast(0)
        ix.editing = false; slideRunning = false
        handler.removeCallbacks(slideTick)
        setQueue(); showEntity()
    }
    private fun startShow() { if (scope.isEmpty()) return; slideRunning = true; slideRemaining = slideSeconds; showEntity(); handler.removeCallbacks(slideTick); handler.postDelayed(slideTick, 1000) }
    private fun stopShow() { slideRunning = false; handler.removeCallbacks(slideTick); showEntity() }

    private fun showEntity() {
        val it = curItem ?: return
        val w = Widgets.of(it.type)
        val dur = if (slideRunning) slideSeconds * 1000L else -1L
        val ctr = if (scope.size > 1) "   (" + (idx + 1) + "/" + scope.size + ")" else ""
        val mb = MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, w.playerTitle(this, it, ix) ?: (it.title ?: it.entity ?: ""))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, w.playerArtist(this, it, ix) + ctr)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, w.playerArt(this, it, ix))
        session.setMetadata(mb.build())
        applyState()
    }

    private fun applyState() {
        val it = curItem
        val b = PlaybackStateCompat.Builder()
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
        if (it != null) {
            for (wa in Widgets.of(it.type).actions(this, it, ix)) b.addCustomAction(wa.id, wa.label, wa.icon)
            if (it.type == "number" && ix.editing) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        b.setActions(actions); b.setActiveQueueItemId(idx.toLong())
        val pos = if (slideRunning) slideRemaining * 1000L else 0L
        b.setState(if (slideRunning) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, pos, 0f)
        session.setPlaybackState(b.build())
    }

    private fun setQueue() {
        val q = scope.mapIndexed { i, mid ->
            val item = itemByMedia[mid]
            val d = MediaDescriptionCompat.Builder().setMediaId(mid).setTitle(item?.title ?: mid)
                .setIconBitmap(item?.let { Widgets.icon(this, it, 120) }).build()
            MediaSessionCompat.QueueItem(d, i.toLong())
        }
        session.setQueue(q); session.setQueueTitle(getString(R.string.entities))
    }

    // ---------- browse ----------
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val ex = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, LIST)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
        }
        if (clientPackageName == "com.google.android.googlequicksearchbox") return BrowserRoot("__empty__", ex)
        return BrowserRoot("root", ex)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(buildNodes(parentId))
    }

    private fun buildNodes(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val out = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (parentId == "root") {
            if (conf.tabs.isEmpty()) {
                val d = MediaDescriptionCompat.Builder().setMediaId("err").setTitle("Config error (YAML)").setSubtitle(confError ?: "check autohome.yaml").setIconBitmap(Draw.listIcon(150)).build()
                out.add(MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            } else conf.tabs.forEach { out.add(tabNode(it)) }
        } else {
            childrenByParent[parentId]?.forEach { out.add(node(stableId(it), it)) }
        }
        return out
    }

    private fun tabNode(tab: Tab): MediaBrowserCompat.MediaItem {
        val st = if (tab.style == "grid") GRID else LIST
        val ex = Bundle().apply { putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, st); putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, st) }
        val d = MediaDescriptionCompat.Builder().setMediaId("tab:" + tab.id).setTitle(tab.title).setExtras(ex).build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun node(mid: String, it: Item): MediaBrowserCompat.MediaItem {
        val ex = Bundle()
        if (it.group != null) ex.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it.group)
        if (it.type == "gauge" || it.type == "battery" || it.type == "progress") ex.putDouble(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, pctOf(this, it) / 100.0)
        if (it.items != null) { val st = if (it.style == "grid") GRID else LIST; ex.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, st); ex.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, st) }
        val title = when {
            it.type == "card" -> it.title ?: ""
            it.type in PANEL_TYPES -> it.title ?: getString(if (it.type == "toggle") R.string.panel_toggles else R.string.panel_buttons)
            it.type == "text" -> { val sp = it.sep ?: " · "; (it.title ?: "") + sp + (it.lines ?: listOf()).joinToString(sp) { l -> l.label.replace("\n", " ").trim() + " " + valStr(l.entity) } }
            else -> it.title ?: it.entity ?: ""
        }
        val sub = if (it.type == "card" || it.type == "camera" || it.type == "text") "" else Widgets.of(it.type).browseSub(this, it)
        val d = MediaDescriptionCompat.Builder().setMediaId(mid).setTitle(title).setSubtitle(sub).setIconBitmap(Widgets.icon(this, it, 150)).setExtras(ex).build()
        val flag = if (it.items != null && it.type !in PANEL_TYPES) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        return MediaBrowserCompat.MediaItem(d, flag)
    }

    override fun onDestroy() { slideRunning = false; session.release(); super.onDestroy() }
}
