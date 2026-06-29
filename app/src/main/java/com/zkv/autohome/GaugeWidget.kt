package com.zkv.autohome

import android.graphics.Bitmap

/** Default widget: percentage ring. */
object GaugeWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = Draw.ring(size, pctOf(ctx, it), false)
    override fun browseSub(ctx: RenderCtx, it: Item) = ctx.valStr(it.entity) + " " + unit(it)
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = Draw.ring(480, pctOf(ctx, it))
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) = ctx.valStr(it.entity) + " " + unit(it)
}
