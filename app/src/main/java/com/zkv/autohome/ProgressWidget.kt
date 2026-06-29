package com.zkv.autohome

import android.graphics.Bitmap

/** progress (gradient bar + char bar) and battery (battery icon). */
object ProgressWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap =
        if (it.type == "battery") Draw.batteryIcon(size, pctOf(ctx, it))
        else Draw.vbar(size, pctOf(ctx, it), Draw.grad(it.grad, it.color))

    override fun browseSub(ctx: RenderCtx, it: Item): String {
        val pc = pctOf(ctx, it); val b = it.bar
        return "$pc%  " + Draw.textBar(pc.toDouble(), b?.width ?: 14, b?.fill ?: "█", b?.empty ?: "░")
    }
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap =
        if (it.type == "battery") Draw.batteryIcon(480, pctOf(ctx, it))
        else Draw.vbar(480, pctOf(ctx, it), Draw.grad(it.grad, it.color))

    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) = browseSub(ctx, it)
}
