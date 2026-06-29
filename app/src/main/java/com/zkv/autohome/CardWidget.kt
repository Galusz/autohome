package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Dashboard tile: chart = ring | bars | line | pie | ribbon. Fixed 400px. */
object CardWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = render(ctx, it)
    override fun browseSub(ctx: RenderCtx, it: Item) = ""
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = render(ctx, it)
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) =
        if (it.entity != null) ctx.valStr(it.entity) + unit(it) else ""

    private fun render(ctx: RenderCtx, it: Item): Bitmap = when (it.chart) {
        "pie" -> pieCard(ctx, it)
        "ribbon" -> ribbonCard(ctx, it)
        "bars" -> chartCard(ctx, it, Draw.bars(240, ctx.history(it) ?: intArrayOf(20, 45, 70, 55, 90, 60)))
        "line" -> chartCard(ctx, it, Draw.line(240, ctx.history(it) ?: intArrayOf(40, 55, 50, 65, 72, 68, 80)))
        else -> ringCard(ctx, it)
    }

    private fun partVals(ctx: RenderCtx, item: Item): List<Triple<String?, Double, Int>> =
        item.parts?.map { Triple(it.label, ctx.valNum(it.entity) ?: 0.0, Draw.parseCol(it.color)) } ?: listOf()

    private fun ringCard(ctx: RenderCtx, item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pc = pctOf(ctx, item)
        val cx = s / 2f; val cy = s * 0.50f; val rOut = s * 0.37f
        val rr = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.15f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(45, 48, 56); c.drawArc(rr, 135f, 270f, false, p)
        p.color = if (pc < 25) Color.rgb(214, 90, 90) else Color.rgb(54, 201, 141); c.drawArc(rr, 135f, 270f * pc / 100f, false, p)
        p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.11f
        Draw.outlinedText(c, p, ctx.valStr(item.entity) + unit(item), cx, cy + s * 0.04f, Color.WHITE)
        p.textSize = s * 0.06f
        p.textAlign = Paint.Align.LEFT; Draw.outlinedText(c, p, Draw.fmtNum(item.min), s * 0.1f, s * 0.97f, Color.rgb(185, 191, 203))
        p.textAlign = Paint.Align.RIGHT; Draw.outlinedText(c, p, Draw.fmtNum(item.max), s * 0.9f, s * 0.97f, Color.rgb(185, 191, 203))
        return bmp
    }

    private fun chartCard(ctx: RenderCtx, item: Item, chart: Bitmap): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(chart, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), null)
        p.textAlign = Paint.Align.LEFT; p.textSize = s * 0.11f
        Draw.outlinedText(c, p, ctx.valStr(item.entity) + unit(item), s * 0.05f, s * 0.15f, Color.WHITE)
        ctx.range(item)?.let { rg ->
            p.textSize = s * 0.06f; p.textAlign = Paint.Align.RIGHT
            Draw.outlinedText(c, p, Draw.fmtNum(rg.second), s * 0.95f, s * 0.15f, Color.rgb(185, 191, 203))
            Draw.outlinedText(c, p, Draw.fmtNum(rg.first), s * 0.95f, s * 0.95f, Color.rgb(185, 191, 203))
        }
        return bmp
    }

    private fun pieCard(ctx: RenderCtx, item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val parts = partVals(ctx, item)
        val sum = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val cx = s / 2f; val cy = s * 0.50f; val rOut = s * 0.36f
        val rr = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.15f
        var start = -90f
        for (t in parts) {
            val sweep = (360.0 * t.second / sum).toFloat()
            p.color = t.third; c.drawArc(rr, start, sweep - 2f, false, p); start += sweep
        }
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.11f
        c.drawText(Draw.fmtNum(sum) + unit(item), cx, cy + s * 0.04f, p)
        return bmp
    }

    private fun ribbonCard(ctx: RenderCtx, item: Item): Bitmap {
        val s = 400; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val parts = partVals(ctx, item)
        val sum = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val n = parts.size.coerceAtLeast(1)
        val top = s * 0.08f; val rowH = (s * 0.84f) / n
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
}
