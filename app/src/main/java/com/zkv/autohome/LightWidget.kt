package com.zkv.autohome

import android.graphics.Bitmap
import org.json.JSONObject

object LightWidget : Widget {
    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = Draw.lightArt(size, ctx.isOn(it.entity), lightPct(ctx, it))
    override fun browseSub(ctx: RenderCtx, it: Item) = if (ctx.isOn(it.entity)) "${lightPct(ctx, it)}% ON" else "OFF"
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap = Draw.lightArt(480, ctx.isOn(it.entity), lightPct(ctx, it))
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) =
        if (ctx.isOn(it.entity)) "${lightPct(ctx, it)}%  " + ctx.str(R.string.brightness) else ctx.str(R.string.state_off)
    override fun actions(ctx: RenderCtx, it: Item, ix: Interaction): List<WAction> {
        val on = ctx.isOn(it.entity)
        val mdi = MdiIcons.res(if (on && it.iconOn != null) it.iconOn else it.icon)
        val tog = mdi ?: if (on) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off
        return listOf(
            WAction("TOGGLE", ctx.str(if (on) R.string.act_turn_off else R.string.act_turn_on), tog),
            WAction("DOWN", ctx.str(R.string.act_dimmer), R.drawable.ic_minus),
            WAction("UP", ctx.str(R.string.act_brighter), R.drawable.ic_plus)
        )
    }
    override fun onAction(ctx: RenderCtx, it: Item, ix: Interaction, action: String) {
        val e = it.entity ?: return
        when (action) {
            "TOGGLE" -> { val on = ctx.isOn(e); ctx.ha?.callService(e.substringBefore("."), if (on) "turn_off" else "turn_on", e); ctx.setState(e, if (on) "off" else "on") }
            "UP" -> setBright(ctx, e, lightPct(ctx, it) + 10)
            "DOWN" -> setBright(ctx, e, lightPct(ctx, it) - 10)
        }
    }
    private fun setBright(ctx: RenderCtx, e: String, pct: Int) {
        val v = pct.coerceIn(0, 100)
        if (v <= 0) { ctx.ha?.callService("light", "turn_off", e); ctx.setState(e, "off") }
        else {
            ctx.ha?.callService("light", "turn_on", e, JSONObject().put("brightness_pct", v))
            ctx.setState(e, "on"); ctx.setAttrInt(e, "brightness", v * 255 / 100)
        }
    }
}
