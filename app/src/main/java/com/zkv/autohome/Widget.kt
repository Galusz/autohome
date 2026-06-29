package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject

/** Live data access for widgets, implemented by the service. */
interface RenderCtx {
    fun valStr(e: String?): String
    fun valNum(e: String?): Double?
    fun isOn(e: String?): Boolean
    fun attr(e: String?): JSONObject?
    fun camera(e: String?): Bitmap?
    fun history(it: Item): IntArray?
    fun range(it: Item): Pair<Double, Double>?
    fun setState(e: String, state: String)
    fun setAttrInt(e: String, key: String, value: Int)
    fun renderRes(resId: Int, size: Int, tint: Int): Bitmap
    fun str(id: Int): String
    val ha: HaClient?
}

/** Transient per-open-item interaction state held by the service. */
class Interaction { var editing = false; var editVal = 0.0; var flash = false; var flashIdx = -1 }

data class WAction(val id: String, val label: String, val icon: Int)

val PANEL_TYPES = setOf("toggle", "button")
fun unit(it: Item) = it.unit ?: ""
fun isClimate(it: Item?) = it?.entity?.startsWith("climate.") == true
fun pctOf(ctx: RenderCtx, it: Item): Int {
    val v = ctx.valNum(it.entity) ?: return 0
    return (((v - it.min) / (it.max - it.min)) * 100.0).toInt().coerceIn(0, 100)
}
fun numCurrent(ctx: RenderCtx, it: Item): Double {
    val e = it.entity ?: return it.min
    return if (e.startsWith("climate.")) ctx.attr(e)?.optDouble("temperature", it.min) ?: it.min
    else ctx.valNum(e) ?: it.min
}
fun lightPct(ctx: RenderCtx, it: Item): Int {
    val b = ctx.attr(it.entity)?.optInt("brightness", -1) ?: -1
    return if (b >= 0) ((b * 100 + 127) / 255) else if (ctx.isOn(it.entity)) 100 else 0
}

interface Widget {
    fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap
    fun browseSub(ctx: RenderCtx, it: Item): String
    fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap
    fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction): String
    fun playerTitle(ctx: RenderCtx, it: Item, ix: Interaction): String? = null
    fun actions(ctx: RenderCtx, it: Item, ix: Interaction): List<WAction> = emptyList()
    fun onAction(ctx: RenderCtx, it: Item, ix: Interaction, action: String) {}
}

object Widgets {
    private val map: Map<String, Widget> = mapOf(
        "toggle" to ButtonsWidget, "button" to ButtonsWidget, "light" to LightWidget,
        "number" to NumberWidget, "gauge" to GaugeWidget,
        "progress" to ProgressWidget, "battery" to ProgressWidget,
        "text" to TextWidget, "card" to CardWidget, "camera" to CameraWidget, "room" to RoomWidget
    )
    fun of(type: String): Widget = map[type] ?: GaugeWidget

    /** List icon honoring explicit it.icon overrides, else the widget default. */
    fun icon(ctx: RenderCtx, it: Item, size: Int): Bitmap {
        if (it.type == "camera") return of("camera").listIcon(ctx, it, size)
        val on = ctx.isOn(it.entity)
        val ic = if (on && it.iconOn != null) it.iconOn else it.icon
        val mdi = MdiIcons.res(ic)
        return when {
            ic == "toggle" -> Draw.switchArt(size, on)
            ic == "battery" -> Draw.batteryIcon(size, pctOf(ctx, it))
            ic == "progress" -> Draw.vbar(size, pctOf(ctx, it), Draw.grad(it.grad, it.color))
            ic == "gauge" || ic == "ring" -> Draw.ring(size, pctOf(ctx, it), false)
            ic == "list" -> Draw.listIcon(size)
            mdi != null -> {
                val tint = if (it.iconOn != null) (if (on) Color.rgb(54, 201, 141) else Color.rgb(150, 156, 168)) else Color.WHITE
                ctx.renderRes(mdi, size, tint)
            }
            ic != null && ic.length == 1 && ic[0].isLetter() -> Draw.roomIcon(ic, Draw.parseCol(it.color))
            ic != null && ic.isNotBlank() -> Draw.glyph(size, ic)
            else -> of(it.type).listIcon(ctx, it, size)
        }
    }
}
