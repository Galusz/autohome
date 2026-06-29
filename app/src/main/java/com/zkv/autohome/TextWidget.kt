package com.zkv.autohome

import android.graphics.Bitmap

object TextWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = Draw.listIcon(size)
    override fun browseSub(ctx: RenderCtx, it: Item) = ""   // text shows values in the title (handled by service)
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = Draw.textPanel(560, rows(ctx, it))
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) =
        rows(ctx, it).joinToString(it.sep ?: " · ") { r -> r.first + " " + r.second }

    private fun rows(ctx: RenderCtx, it: Item): List<Pair<String, String>> =
        (it.lines ?: listOf()).map { l -> Pair(l.label.replace("\n", " ").trim(), ctx.valStr(l.entity)) }
}
