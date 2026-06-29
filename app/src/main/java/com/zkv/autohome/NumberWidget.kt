package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject

/** number / input_number / climate (climate via climate.* entity). */
object NumberWidget : Widget {
    private val EDIT_COLOR = Color.rgb(86, 140, 255)

    private fun modeLabel(ctx: RenderCtx, mode: String): String = when (mode) {
        "heat" -> ctx.str(R.string.mode_heat)
        "cool" -> ctx.str(R.string.mode_cool)
        "off" -> ctx.str(R.string.mode_off)
        "heat_cool", "auto" -> ctx.str(R.string.mode_auto)
        "dry" -> ctx.str(R.string.mode_dry)
        "fan_only" -> ctx.str(R.string.mode_fan)
        else -> mode.uppercase()
    }

    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap = Draw.ring(size, pctOf(ctx, it), false)
    override fun browseSub(ctx: RenderCtx, it: Item) =
        if (isClimate(it)) modeLabel(ctx, ctx.valStr(it.entity)) + " " + Draw.fmt(numCurrent(ctx, it)) + unit(it)
        else Draw.fmt(numCurrent(ctx, it)) + " " + unit(it)

    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap {
        val v = if (ix.editing) ix.editVal else numCurrent(ctx, it)
        return if (isClimate(it)) {
            val label = if (ix.editing) ctx.str(R.string.editing) else modeLabel(ctx, ctx.valStr(it.entity))
            val color = if (ix.editing) EDIT_COLOR else Draw.modeColor(ctx.valStr(it.entity))
            Draw.climateArt(480, v, it.min, it.max, label, color)
        } else Draw.numberArt(480, v, it.min, it.max, unit(it), ix.editing, ctx.str(R.string.editing))
    }
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction): String {
        val v = if (ix.editing) ix.editVal else numCurrent(ctx, it)
        if (ix.editing) return ctx.str(R.string.number_hint)
        val set = "  -  " + ctx.str(R.string.set_hint)
        return if (isClimate(it)) modeLabel(ctx, ctx.valStr(it.entity)) + "   " + Draw.fmt(v) + unit(it) + set
        else Draw.fmt(v) + " " + unit(it) + set
    }
    override fun actions(ctx: RenderCtx, it: Item, ix: Interaction): List<WAction> {
        if (ix.editing) return listOf(WAction("OK", ctx.str(R.string.act_save), R.drawable.ic_check))
        val l = mutableListOf(WAction("SET", ctx.str(R.string.act_set), R.drawable.ic_edit))
        if (isClimate(it)) {
            l.add(WAction("HEAT", ctx.str(R.string.act_heat), R.drawable.ic_fire))
            l.add(WAction("COOL", ctx.str(R.string.act_cool), R.drawable.ic_snow))
            l.add(WAction("OFFC", ctx.str(R.string.act_off), R.drawable.ic_power))
        }
        return l
    }
    override fun onAction(ctx: RenderCtx, it: Item, ix: Interaction, action: String) {
        val e = it.entity ?: return
        when (action) {
            "SET" -> { ix.editing = true; ix.editVal = numCurrent(ctx, it) }
            "OK" -> {
                if (isClimate(it)) ctx.ha?.callService("climate", "set_temperature", e, JSONObject().put("temperature", ix.editVal))
                else ctx.ha?.callService(e.substringBefore("."), "set_value", e, JSONObject().put("value", ix.editVal))
                ix.editing = false
            }
            "HEAT" -> setMode(ctx, e, "heat")
            "COOL" -> setMode(ctx, e, "cool")
            "OFFC" -> setMode(ctx, e, "off")
        }
    }
    private fun setMode(ctx: RenderCtx, e: String, m: String) {
        ctx.ha?.callService("climate", "set_hvac_mode", e, JSONObject().put("hvac_mode", m)); ctx.setState(e, m)
    }
}
