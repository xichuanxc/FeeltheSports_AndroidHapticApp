package com.feelthesports.hapticactuator.timeline

import org.json.JSONObject

data class HapticEvent(
    val time: Double,       // seconds, from wire format
    val intensity: Float,   // 0..1, per-video relative — never re-normalise
    val type: String,       // "strike", "bounce", or unknown (fall back, never crash)
    val db: Double,
    val hfRatio: Double,
    val centroid: Double,
)

class Timeline(events: List<HapticEvent>) {

    val events: List<HapticEvent> = events.sortedBy { it.time }

    /** Index of the first event with time >= [t], or events.size if none. */
    fun indexFrom(t: Double): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].time < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun parse(json: JSONObject): Timeline {
            val arr = json.getJSONArray("events")
            val events = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                HapticEvent(
                    time      = e.getDouble("time"),
                    intensity = e.getDouble("intensity").toFloat(),
                    type      = e.optString("type", "strike"),
                    db        = e.optDouble("db", 0.0),
                    hfRatio   = e.optDouble("hf_ratio", 0.0),
                    centroid  = e.optDouble("centroid", 0.0),
                )
            }
            return Timeline(events)
        }

        /** Six-event sequence spread over ~3 s for standalone testing. */
        fun createTest() = Timeline(listOf(
            HapticEvent(0.0,  0.9f, "strike", 0.0, 0.0, 0.0),
            HapticEvent(0.4,  0.6f, "bounce", 0.0, 0.0, 0.0),
            HapticEvent(0.9,  0.8f, "strike", 0.0, 0.0, 0.0),
            HapticEvent(1.5,  0.5f, "bounce", 0.0, 0.0, 0.0),
            HapticEvent(2.0,  0.9f, "strike", 0.0, 0.0, 0.0),
            HapticEvent(2.6,  0.7f, "bounce", 0.0, 0.0, 0.0),
        ))
    }
}
