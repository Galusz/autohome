package com.zkv.autohome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

class HaClient(
    private val url: String,
    private val token: String,
    private val onStates: (Map<String, Pair<String, JSONObject>>) -> Unit,
    private val onChange: (String, String, JSONObject) -> Unit
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private var id = 1
    private val next get() = id++

    fun connect() {
        if (url.isBlank() || token.isBlank()) return
        val wsUrl = url.replace("https://", "wss://").replace("http://", "ws://") + "/api/websocket"
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (_: Exception) {}
            }
        })
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "auth_required" -> send(JSONObject().put("type", "auth").put("access_token", token))
            "auth_ok" -> { getStates(); subscribe() }
            "result" -> o.optJSONArray("result")?.let { parseStates(it) }
            "event" -> {
                val ns = o.optJSONObject("event")?.optJSONObject("data")?.optJSONObject("new_state") ?: return
                onChange(ns.getString("entity_id"), ns.optString("state"), ns.optJSONObject("attributes") ?: JSONObject())
            }
        }
    }

    private fun getStates() = send(JSONObject().put("id", next).put("type", "get_states"))
    private fun subscribe() = send(JSONObject().put("id", next).put("type", "subscribe_events").put("event_type", "state_changed"))

    private fun parseStates(arr: JSONArray) {
        val map = HashMap<String, Pair<String, JSONObject>>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (!s.has("entity_id")) return
            map[s.getString("entity_id")] = Pair(s.optString("state"), s.optJSONObject("attributes") ?: JSONObject())
        }
        if (map.isNotEmpty()) onStates(map)
    }

    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        val sd = (data ?: JSONObject()).put("entity_id", entityId)
        send(JSONObject().put("id", next).put("type", "call_service").put("domain", domain).put("service", service).put("service_data", sd))
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    fun getSnapshot(entity: String, cb: (Bitmap) -> Unit) {
        if (url.isBlank() || token.isBlank()) return
        val req = Request.Builder().url("$url/api/camera_proxy/$entity").header("Authorization", "Bearer $token").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val bytes = r.body?.bytes() ?: return
                    val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }
                    if (bmp != null) cb(bmp)
                }
            }
        })
    }

    fun getHistory(entity: String, minutes: Long, cb: (List<Double>) -> Unit) {
        if (url.isBlank() || token.isBlank()) return
        val start = Instant.now().minusSeconds(minutes * 60).toString()
        val req = Request.Builder()
            .url("$url/api/history/period/$start?filter_entity_id=$entity&minimal_response&no_attributes")
            .header("Authorization", "Bearer $token").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body?.string() ?: return
                    try {
                        val arr = JSONArray(body)
                        if (arr.length() == 0) return
                        val series = arr.getJSONArray(0)
                        val out = ArrayList<Double>()
                        for (i in 0 until series.length()) {
                            series.getJSONObject(i).optString("state").toDoubleOrNull()?.let { out.add(it) }
                        }
                        if (out.isNotEmpty()) cb(out)
                    } catch (_: Exception) {}
                }
            }
        })
    }
}
