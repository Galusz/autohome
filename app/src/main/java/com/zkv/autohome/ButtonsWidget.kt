package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject

/**
 * Unified panel for `toggle` and `button`. Takes a single `entity` or `items:` (1-4).
 *  - toggle: each item is on/off (icon tinted green/grey, or switch pill)
 *  - button: each item is impulse (icon white, amber flash, or button circle)
 * Layout adapts to count: 1 big, 2 stacked, 3-4 corners.
 */
object ButtonsWidget : Widget {
    private val NUMS = intArrayOf(
        R.drawable.ic_num_1, R.drawable.ic_num_2, R.drawable.ic_num_3,
        R.drawable.ic_num_4, R.drawable.ic_num_5, R.drawable.ic_num_6
    )
    private val AMBER = Color.rgb(255, 200, 80)
    private val GREEN = Color.rgb(54, 201, 141)
    private val GREY = Color.rgb(150, 156, 168)

    private fun subs(it: Item) = it.items?.take(4) ?: listOf(it)
    private fun sep(it: Item) = it.sep ?: " · "
    private fun isToggle(it: Item) = it.type == "toggle"

    private fun cell(ctx: RenderCtx, s: Item, toggle: Boolean, flash: Boolean, num: Int): Bitmap {
        val on = ctx.isOn(s.entity)
        val res = MdiIcons.res(if (toggle && on && s.iconOn != null) s.iconOn else s.icon)
        if (res != null) {
            val tint = if (toggle) (if (on) GREEN else GREY) else (if (flash) AMBER else Color.WHITE)
            return ctx.renderRes(res, 200, tint)
        }
        val base = if (toggle) Draw.switchArt(200, on) else Draw.buttonArt(200, flash)
        if (num > 0) Draw.numberBadge(base, num)
        return base
    }

    override fun listIcon(ctx: RenderCtx, it: Item, size: Int): Bitmap {
        val tg = isToggle(it); val items = subs(it)
        return Draw.iconGrid(size, items.mapIndexed { i, s -> cell(ctx, s, tg, false, if (items.size > 1) i + 1 else 0) })
    }
    private fun summary(ctx: RenderCtx, it: Item): String {
        val items = subs(it); val on = items.count { s -> ctx.isOn(s.entity) }
        return "ON $on${sep(it)}OFF ${items.size - on}"
    }
    override fun browseSub(ctx: RenderCtx, it: Item) = if (isToggle(it)) summary(ctx, it) else ""
    override fun playerTitle(ctx: RenderCtx, it: Item, ix: Interaction) =
        subs(it).joinToString(sep(it)) { s -> s.title ?: s.entity ?: "" }
    override fun playerArt(ctx: RenderCtx, it: Item, ix: Interaction): Bitmap {
        val tg = isToggle(it); val items = subs(it)
        return Draw.iconGrid(560, items.mapIndexed { i, s -> cell(ctx, s, tg, ix.flash && ix.flashIdx == i, if (items.size > 1) i + 1 else 0) })
    }
    override fun playerArtist(ctx: RenderCtx, it: Item, ix: Interaction) =
        if (!isToggle(it)) (if (ix.flash) ctx.str(R.string.pushed) else "") else summary(ctx, it)

    override fun actions(ctx: RenderCtx, it: Item, ix: Interaction): List<WAction> {
        val items = subs(it); val n = items.size; val tg = isToggle(it)
        val base = items.mapIndexed { i, s ->
            val on = ctx.isOn(s.entity)
            val mdi = MdiIcons.res(if (tg && on && s.iconOn != null) s.iconOn else s.icon)
            val icon = mdi ?: if (n > 1) NUMS[i]
            else if (tg) (if (on) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off) else R.drawable.ic_push
            WAction("B$i", s.title ?: ((i + 1).toString()), icon)
        }
        if (n <= 2) return base
        val l = (n + 1) / 2
        val arr = arrayOfNulls<WAction>(n)
        for (i in 0 until n) { val a = if (i < l) 2 * (l - 1 - i) else 2 * (i - l) + 1; arr[a] = base[i] }
        return arr.filterNotNull()
    }

    override fun onAction(ctx: RenderCtx, it: Item, ix: Interaction, action: String) {
        if (!action.startsWith("B")) return
        val i = action.drop(1).toIntOrNull() ?: return
        val s = subs(it).getOrNull(i) ?: return
        val e = s.entity ?: return
        val dom = e.substringBefore(".")
        if (dom in listOf("switch", "light", "fan", "input_boolean")) {
            val on = ctx.isOn(e)
            ctx.ha?.callService(dom, if (on) "turn_off" else "turn_on", e)
            ctx.setState(e, if (on) "off" else "on")
        } else {
            val svc = s.service ?: when (dom) {
                "button" -> "button.press"; "scene", "script" -> "$dom.turn_on"
                "cover" -> "cover.toggle"; else -> "homeassistant.toggle"
            }
            ctx.ha?.callService(svc.substringBefore("."), svc.substringAfter("."), e)
        }
        if (!isToggle(it)) { ix.flash = true; ix.flashIdx = i }
    }

    private fun stateLabel(ctx: RenderCtx, s: Item): String {
        val e = s.entity ?: return ""
        return when (e.substringBefore(".")) {
            "switch", "light", "input_boolean", "fan" -> if (ctx.isOn(e)) "ON" else "OFF"
            "cover" -> ctx.valStr(e).uppercase()
            "scene", "script", "button" -> "▶"
            else -> (ctx.valStr(e) + " " + unit(s)).trim().uppercase()
        }
    }
}
