package com.feelthesports.hapticactuator.timeline

import org.json.JSONObject

data class HapticEvent(
    val time: Double,           // seconds, from wire format
    val intensity: Float,       // 0..1, per-video relative — never re-normalise
    val visionType: String?,    // "strike", "bounce", or null (undetermined / no vision data)
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
                    time       = e.getDouble("time"),
                    intensity  = e.getDouble("intensity").toFloat(),
                    visionType = if (!e.has("vision_type") || e.isNull("vision_type")) null
                                 else e.getString("vision_type"),
                )
            }
            return Timeline(events)
        }

        /** Six-event sequence spread over ~3 s for standalone testing. */
        fun createTest() = Timeline(listOf(
            HapticEvent(0.0,  0.9f, "strike"),
            HapticEvent(0.4,  0.6f, "bounce"),
            HapticEvent(0.9,  0.8f, "strike"),
            HapticEvent(1.5,  0.5f, "bounce"),
            HapticEvent(2.0,  0.9f, "strike"),
            HapticEvent(2.6,  0.7f, "bounce"),
        ))
    }
}
