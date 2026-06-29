package com.zkv.autohome

import android.graphics.Bitmap

object CameraWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = Draw.cameraCard(ctx.camera(it.entity), 512, crop = true)
    override fun browseSub(ctx: RenderCtx, it: Item) = ""
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = Draw.cameraCard(ctx.camera(it.entity), 720, crop = false)
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) = ""
}
