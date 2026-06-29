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
import java.util.Locale

/** Pure bitmap renderers + drawing helpers. No app/HA state — callers pass resolved values. */
object Draw {
    fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    fun fmtNum(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
    fun parseCol(s: String?): Int = try { Color.parseColor(s ?: "#568CFF") } catch (e: Exception) { Color.rgb(86, 140, 255) }

    fun outlinedText(c: Canvas, p: Paint, text: String, x: Float, y: Float, fill: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = p.textSize * 0.16f; p.color = Color.argb(210, 12, 13, 16)
        c.drawText(text, x, y, p)
        p.style = Paint.Style.FILL; p.color = fill
        c.drawText(text, x, y, p)
    }

    fun textBar(pct: Double, width: Int, fill: String, empty: String): String {
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

    fun grad(gradList: List<String>?, color: String?): IntArray {
        val g = gradList?.mapNotNull { runCatching { Color.parseColor(it) }.getOrNull() }
        return when {
            g != null && g.size >= 2 -> g.toIntArray()
            g != null && g.size == 1 -> intArrayOf(g[0], g[0])
            color != null -> { val c = parseCol(color); intArrayOf(c, c) }
            else -> intArrayOf(Color.rgb(214, 90, 90), Color.rgb(224, 180, 60), Color.rgb(54, 201, 141))
        }
    }

    fun ring(size: Int, percent: Int, label: Boolean = true): Bitmap {
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

    fun switchArt(size: Int, on: Boolean): Bitmap {
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

    fun toggles(size: Int, on: List<Boolean>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = on.size.coerceIn(1, 4)
        val tw = size * 0.62f                       // same length as switchArt
        val left = (size - tw) / 2f
        val gap = size * 0.06f
        val rowH = (size - gap * (n - 1)) / n
        val th = (rowH * 0.82f).coerceAtMost(size * 0.34f)   // thick pill like switchArt
        for (i in 0 until n) {
            val cy = i * (rowH + gap) + rowH / 2f
            p.color = if (on[i]) Color.rgb(54, 201, 141) else Color.rgb(70, 74, 84)
            c.drawRoundRect(RectF(left, cy - th / 2f, left + tw, cy + th / 2f), th / 2f, th / 2f, p)
            val r = th * 0.5f - th * 0.083f                  // knob ratio = switchArt
            val kx = if (on[i]) left + tw - th / 2f else left + th / 2f
            p.color = Color.WHITE; c.drawCircle(kx, cy, r, p)
        }
        return bmp
    }

    fun togglePanel(size: Int, rows: List<Pair<String, Boolean>>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = rows.size.coerceAtLeast(1)
        val pad = size * 0.06f
        val lh = (size - 2 * pad) / n
        val ts = (lh * 0.32f).coerceAtMost(size * 0.085f)
        val tw = lh * 0.70f; val th = lh * 0.38f
        for (i in rows.indices) {
            val (name, on) = rows[i]
            val cy = pad + lh * (i + 0.5f)
            if (i > 0) { p.style = Paint.Style.STROKE; p.color = Color.rgb(40, 43, 50); p.strokeWidth = 1f; c.drawLine(pad, pad + lh * i, size - pad, pad + lh * i, p) }
            val tleft = pad
            p.style = Paint.Style.FILL
            p.color = if (on) Color.rgb(54, 201, 141) else Color.rgb(70, 74, 84)
            c.drawRoundRect(RectF(tleft, cy - th / 2f, tleft + tw, cy + th / 2f), th / 2f, th / 2f, p)
            val r = th * 0.5f - th * 0.12f; val kx = if (on) tleft + tw - th / 2f else tleft + th / 2f
            p.color = Color.WHITE; c.drawCircle(kx, cy, r, p)
            p.textSize = ts; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
            c.drawText(name, tleft + tw + pad * 0.7f, cy + ts * 0.35f, p)
        }
        return bmp
    }

    fun dots(size: Int, count: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = count.coerceIn(1, 4)
        val rowH = size.toFloat() / n
        val r = (rowH * 0.24f).coerceAtMost(size * 0.14f)
        for (i in 0 until n) {
            val cy = rowH * (i + 0.5f)
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.5f; p.color = Color.rgb(150, 156, 168)
            c.drawCircle(size / 2f, cy, r, p)
            p.style = Paint.Style.FILL; c.drawCircle(size / 2f, cy, r * 0.42f, p)
        }
        return bmp
    }

    /** Adaptive layout of pre-rendered square cell bitmaps: 1 big, 2 stacked, 3-4 in corners. */
    fun iconGrid(size: Int, cells: List<Bitmap>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val n = cells.size.coerceIn(1, 4)
        fun place(b: Bitmap, l: Float, t: Float, w: Float, h: Float, frac: Float) {
            val s = minOf(w, h) * frac; val cx = l + w / 2f; val cy = t + h / 2f
            c.drawBitmap(b, null, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), null)
        }
        val s = size.toFloat()
        when (n) {
            1 -> place(cells[0], 0f, 0f, s, s, 0.92f)
            2 -> { place(cells[0], 0f, 0f, s, s / 2f, 0.82f); place(cells[1], 0f, s / 2f, s, s / 2f, 0.82f) }
            else -> { val h = s / 2f; for (i in 0 until n) place(cells[i], (i % 2) * h, (i / 2) * h, h, h, 0.86f) }
        }
        return bmp
    }

    /** Draw a number badge (top-right) onto an existing cell bitmap. */
    fun numberBadge(bmp: Bitmap, n: Int) {
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isFakeBoldText = true; p.textAlign = Paint.Align.CENTER; p.textSize = bmp.width * 0.34f
        outlinedText(c, p, n.toString(), bmp.width * 0.80f, bmp.height * 0.32f, Color.WHITE)
    }

    fun iconStack(size: Int, icons: List<Bitmap?>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = icons.size.coerceIn(1, 4)
        val rowH = size.toFloat() / n
        val box = (rowH * 0.82f).coerceAtMost(size * 0.46f)
        for (i in 0 until n) {
            val cy = rowH * (i + 0.5f)
            val ic = icons.getOrNull(i)
            if (ic != null) c.drawBitmap(ic, null, RectF(size / 2f - box / 2f, cy - box / 2f, size / 2f + box / 2f, cy + box / 2f), null)
            else { p.style = Paint.Style.FILL; p.color = Color.rgb(150, 156, 168); c.drawCircle(size / 2f, cy, rowH * 0.14f, p) }
        }
        return bmp
    }

    fun namePanel(size: Int, rows: List<Triple<String, Boolean, Bitmap?>>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = rows.size.coerceAtLeast(1)
        val pad = size * 0.06f
        val lh = (size - 2 * pad) / n
        val ts = (lh * 0.34f).coerceAtMost(size * 0.085f)
        val box = lh * 0.55f
        for (i in rows.indices) {
            val (name, flash, icon) = rows[i]
            val cy = pad + lh * (i + 0.5f)
            if (i > 0) { p.style = Paint.Style.STROKE; p.color = Color.rgb(40, 43, 50); p.strokeWidth = 1f; c.drawLine(pad, pad + lh * i, size - pad, pad + lh * i, p) }
            if (icon != null) {
                c.drawBitmap(icon, null, RectF(pad, cy - box / 2f, pad + box, cy + box / 2f), null)
            } else {
                p.style = Paint.Style.FILL; p.color = if (flash) Color.rgb(255, 200, 80) else Color.rgb(150, 156, 168)
                c.drawCircle(pad + box / 2f, cy, if (flash) lh * 0.17f else lh * 0.13f, p)
            }
            p.color = if (flash) Color.rgb(255, 200, 80) else Color.WHITE; p.textSize = ts; p.textAlign = Paint.Align.LEFT
            c.drawText(name, pad + box + pad * 0.4f, cy + ts * 0.35f, p)
        }
        return bmp
    }

    fun buttonArt(size: Int, flash: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f; val cy = size / 2f; val r = size * 0.26f
        val base = if (flash) Color.rgb(255, 200, 80) else Color.rgb(150, 156, 168)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.05f; p.color = base
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.42f, p)
        return bmp
    }

    fun batteryIcon(size: Int, pct: Int, charging: Boolean = false): Bitmap {
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

    fun vbar(size: Int, pct: Int, cols: IntArray): Bitmap {
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

    fun bars(size: Int, raw: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = Color.rgb(86, 140, 255)
        val maxBars = 12
        val ds = if (raw.size > maxBars) IntArray(maxBars) { i ->
            val a = i * raw.size / maxBars; val b = ((i + 1) * raw.size / maxBars).coerceAtMost(raw.size)
            if (b > a) (a until b).sumOf { raw[it] } / (b - a) else raw[a.coerceIn(0, raw.size - 1)]
        } else raw
        val mn = ds.minOrNull() ?: 0; val mx = ds.maxOrNull() ?: 100
        val h = IntArray(ds.size) { i -> if (mx > mn) (8 + (ds[i] - mn).toFloat() / (mx - mn) * 92).toInt() else 50 }
        val n = h.size; val gap = size * 0.03f; val bw = (size - gap * (n + 1)) / n
        for (i in 0 until n) {
            val bh = size * 0.84f * h[i] / 100f; val x = gap + i * (bw + gap)
            c.drawRoundRect(RectF(x, size * 0.93f - bh, x + bw, size * 0.93f), 6f, 6f, p)
        }
        return bmp
    }

    fun line(size: Int, v: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.022f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = Color.rgb(54, 201, 141)
        val top = size * 0.08f; val bot = size * 0.92f; val left = size * 0.0f; val right = size * 1.0f
        val n = v.size
        if (n == 0) return bmp
        val xs = FloatArray(n) { i -> left + (right - left) * i / (n - 1).coerceAtLeast(1) }
        val ys = FloatArray(n) { i -> bot - (bot - top) * v[i] / 100f }
        val path = Path(); path.moveTo(xs[0], ys[0])
        for (i in 1 until n) {
            val mx = (xs[i - 1] + xs[i]) / 2f; val my = (ys[i - 1] + ys[i]) / 2f
            path.quadTo(xs[i - 1], ys[i - 1], mx, my)
        }
        path.lineTo(xs[n - 1], ys[n - 1])
        c.drawPath(path, p); return bmp
    }

    fun textPanel(size: Int, rows: List<Pair<String, String>>): Bitmap {
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

    fun listIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(150, 156, 168); p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = size * 0.07f
        for (i in 0..2) { val y = size * (0.32f + i * 0.18f); c.drawLine(size * 0.28f, y, size * 0.72f, y, p) }
        return bmp
    }

    fun roomIcon(letter: String, col: Int): Bitmap {
        val s = 160; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = col; c.drawRoundRect(RectF(8f, 8f, s - 8f, s - 8f), 28f, 28f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.5f
        c.drawText(letter, s / 2f, s / 2f + s * 0.18f, p); return bmp
    }

    fun glyph(size: Int, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.62f; p.color = Color.WHITE
        c.drawText(text, size / 2f, size * 0.7f, p)
        return bmp
    }

    fun numberArt(size: Int, v: Double, vmin: Double, vmax: Double, u: String, editing: Boolean, editLabel: String): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pct = (((v - vmin) / (vmax - vmin)) * 100.0).toFloat()
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = if (editing) Color.rgb(86, 140, 255) else Color.rgb(54, 201, 141)
        c.drawArc(rr, 135f, 270f * pct / 100f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.21f
        c.drawText(fmt(v), size / 2f, size / 2f + size * 0.02f, p)
        p.color = Color.rgb(150, 156, 168); p.textSize = size * 0.085f; c.drawText(u, size / 2f, size * 0.64f, p)
        if (editing) { p.textSize = size * 0.075f; p.color = Color.rgb(86, 140, 255); c.drawText(editLabel, size / 2f, size * 0.86f, p) }
        return bmp
    }

    fun climateArt(size: Int, v: Double, vmin: Double, vmax: Double, label: String, arcColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pct = (((v - vmin) / (vmax - vmin)) * 100.0).toFloat()
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = arcColor; c.drawArc(rr, 135f, 270f * pct / 100f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.21f
        c.drawText(fmt(v) + "°", size / 2f, size / 2f + size * 0.02f, p)
        p.color = arcColor; p.textSize = size * 0.08f
        c.drawText(label, size / 2f, size * 0.66f, p)
        return bmp
    }

    fun lightArt(size: Int, on: Boolean, pct: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pad = size * 0.14f; val rr = RectF(pad, pad, size - pad, size - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.10f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        if (on) {
            val t = pct / 100f
            p.color = Color.rgb((120 + 135 * t).toInt(), (95 + 115 * t).toInt(), (40 + 50 * t).toInt())
            c.drawArc(rr, 135f, 270f * pct / 100f, false, p)
        }
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.color = if (on) Color.WHITE else Color.rgb(110, 114, 124)
        p.textSize = size * 0.22f
        c.drawText(if (on) "$pct%" else "OFF", size / 2f, size / 2f + size * 0.04f, p)
        p.textSize = size * 0.1f
        c.drawText("💡", size / 2f, size * 0.72f, p)
        return bmp
    }

    fun cameraCard(snap: Bitmap?, size: Int, crop: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
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

    fun modeColor(s: String): Int = when (s) {
        "heat" -> Color.rgb(255, 120, 60); "cool" -> Color.rgb(80, 160, 240)
        "off" -> Color.rgb(110, 114, 124); "heat_cool", "auto" -> Color.rgb(54, 201, 141)
        else -> Color.rgb(150, 156, 168)
    }
}
