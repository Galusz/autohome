package com.zkv.autohome

import android.content.Context
import org.yaml.snakeyaml.Yaml

data class BarConf(val fill: String, val empty: String, val width: Int)
data class Line(val label: String, val entity: String)
data class Part(val entity: String, val label: String?, val color: String?)

data class Item(
    val type: String,
    val entity: String?,
    val title: String?,
    val group: String?,
    val icon: String?,
    val color: String?,
    val unit: String?,
    val min: Double,
    val max: Double,
    val step: Double,
    val bar: BarConf?,
    val chart: String?,
    val service: String?,
    val stat: String?,
    val lines: List<Line>?,
    val items: List<Item>?,
    val grad: List<String>?,
    val parts: List<Part>?,
    val hours: Double?,
    val minutes: Double?,
    val style: String?,
    val sep: String?
)

data class Tab(val id: String, val title: String, val style: String, val items: List<Item>)
data class Conf(val url: String, val token: String, val tabs: List<Tab>, val home: List<Item>)

object ConfigLoader {
    fun load(ctx: Context): Conf {
        val ext = java.io.File(ctx.getExternalFilesDir(null), "autohome.yaml")
        val text = if (ext.exists()) ext.readText() else ctx.assets.open("autohome.yaml").bufferedReader().use { it.readText() }
        return loadString(text)
    }

    fun loadString(text: String): Conf {
        val root = Yaml().load<Map<String, Any?>>(text) ?: mapOf()
        val ha = root["homeassistant"] as? Map<*, *> ?: mapOf<String, Any>()
        val tabs = (root["tabs"] as? List<*> ?: listOf<Any>()).map { parseTab(it as Map<*, *>) }
        val home = (root["home"] as? List<*> ?: listOf<Any>()).map { parseItem(it as Map<*, *>) }
        return Conf((ha["url"] as? String ?: "").trimEnd('/'), (ha["token"] as? String ?: ""), tabs, home)
    }

    fun entitiesOf(conf: Conf): List<String> {
        val out = ArrayList<String>()
        fun walk(items: List<Item>) {
            items.forEach { item ->
                item.entity?.let { out.add(it) }
                item.lines?.forEach { out.add(it.entity) }
                item.parts?.forEach { out.add(it.entity) }
                item.items?.let { walk(it) }
            }
        }
        conf.tabs.forEach { walk(it.items) }
        walk(conf.home)
        return out.filter { it.isNotBlank() }.distinct()
    }

    private fun parseTab(m: Map<*, *>): Tab = Tab(
        m["id"] as? String ?: (m["title"] as? String ?: "tab"),
        m["title"] as? String ?: "",
        m["style"] as? String ?: "list",
        (m["items"] as? List<*> ?: listOf<Any>()).map { parseItem(it as Map<*, *>) }
    )

    private fun parseItem(m: Map<*, *>): Item {
        val bar = (m["bar"] as? Map<*, *>)?.let {
            BarConf(it["fill"] as? String ?: "█", it["empty"] as? String ?: "░", (it["width"] as? Number)?.toInt() ?: 14)
        }
        val lines = (m["lines"] as? List<*>)?.map {
            val lm = it as Map<*, *>; Line(lm["label"] as? String ?: "", lm["entity"] as? String ?: "")
        }
        val items = (m["items"] as? List<*>)?.map { parseItem(it as Map<*, *>) }
        return Item(
            m["type"] as? String ?: "gauge",
            m["entity"] as? String, m["title"] as? String, m["group"] as? String,
            m["icon"]?.toString(), m["color"] as? String, m["unit"] as? String,
            (m["min"] as? Number)?.toDouble() ?: 0.0,
            (m["max"] as? Number)?.toDouble() ?: 100.0,
            (m["step"] as? Number)?.toDouble() ?: 1.0,
            bar, m["chart"] as? String, m["service"] as? String, m["statistics"] as? String,
            lines, items, (m["grad"] as? List<*>)?.map { it.toString() },
            (m["parts"] as? List<*>)?.map { val pm = it as Map<*, *>; Part(pm["entity"] as? String ?: "", pm["label"] as? String, pm["color"] as? String) },
            (m["hours"] as? Number)?.toDouble(), (m["minutes"] as? Number)?.toDouble(),
            m["style"] as? String, m["sep"] as? String
        )
    }
}
