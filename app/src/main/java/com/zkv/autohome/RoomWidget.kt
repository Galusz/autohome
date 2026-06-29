package com.zkv.autohome

import android.graphics.Bitmap

object RoomWidget : Widget {
    private fun art(it: Item): Bitmap = Draw.roomIcon((it.title ?: "?").take(1).uppercase(), Draw.parseCol(it.color))
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = art(it)
    override fun browseSub(ctx: RenderCtx, it: Item) = (it.items?.size ?: 0).toString() + " " + ctx.str(R.string.devices)
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = art(it)
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) = ""
}
