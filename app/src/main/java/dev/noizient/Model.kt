package dev.noizient

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToInt

internal enum class Mode(val key: String, val title: String, val engine: Int, val defaults: DoubleArray) {
    Focus("focus", "Focus", 2, doubleArrayOf(68.0,43.0,39.0,45.0,21.0,0.0,78.0,32.0,19.0,9.0,22.0,38.0,58.0,42.0,80.0,36.0,2.0,0.0)),
    Sleep("sleep", "Sleep", 3, doubleArrayOf(58.0,44.0,57.0,0.0,21.0,65.0,80.0,15.0,0.0,0.0,9.0,15.0,28.0,28.0,80.0,36.0,0.0,0.0));

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: Focus
    }
}

internal val layerKeys = listOf("kick","tempo","attack","tight","wave","fire","bird","rustle","activity","panWidth","panSpeed","lofiWarmth")
internal val patterns = listOf("steady", "breathing", "reference")
internal val introOptions = listOf(2,5,10,20,40)
internal val timerOptions = listOf(0,15,30,45,60,90,120)

internal class Store(context: Context) {
    private val storage = context.getSharedPreferences("noizient", Context.MODE_PRIVATE)
    private val controls = Mode.entries.associateWith { mode ->
        val saved = runCatching { JSONArray(storage.getString(mode.key, "[]") ?: "[]") }.getOrDefault(JSONArray())
        DoubleArray(mode.defaults.size) { index ->
            val value = saved.optDouble(index, mode.defaults[index])
            if (!value.isFinite()) mode.defaults[index]
            else when (index) {
                4 -> value.coerceIn(6.0, 100.0)
                16 -> value.coerceIn(0.0, 2.0)
                17 -> value.coerceIn(0.0, 1.0)
                else -> value.coerceIn(0.0, 100.0)
            }
        }
    }
    var mode: Mode
        get() = Mode.from(storage.getString("mode", null))
        set(value) { storage.edit().putString("mode", value.key).apply() }
    var intro: Int
        get() = storage.getInt("intro", 10).takeIf { it in introOptions } ?: 10
        set(value) { storage.edit().putInt("intro", value).apply() }

    fun timer(mode: Mode) = storage.getInt("timer-${mode.key}", 0).takeIf { it in timerOptions } ?: 0
    fun timer(mode: Mode, value: Int) { storage.edit().putInt("timer-${mode.key}", value).apply() }
    fun values(mode: Mode) = controls.getValue(mode).copyOf()
    fun value(mode: Mode, index: Int, value: Double) {
        val values = controls.getValue(mode)
        values[index] = value
        storage.edit().putString(mode.key, JSONArray(values.toList()).toString()).apply()
    }

    fun history(): List<JSONObject> {
        val array = runCatching { JSONArray(storage.getString("history", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull(array::optJSONObject).takeLast(200)
    }

    fun saveHistory(records: List<JSONObject>) {
        storage.edit().putString("history", JSONArray(records.takeLast(200)).toString()).apply()
    }

    fun export(): JSONObject = JSONObject()
        .put("version", 1)
        .put("mode", mode.key)
        .put("introMinutes", intro)
        .put("settings", JSONObject().apply {
            Mode.entries.forEach { mode ->
                put(mode.key, JSONObject()
                    .put("controls", JSONArray(values(mode).toList()))
                    .put("timerMinutes", timer(mode)))
            }
        })
        .put("history", JSONArray(history()))
}

internal fun chooseMix(base: DoubleArray, mode: Mode, history: List<JSONObject>, seed: Long): DoubleArray {
    val ratings = history.filter {
        it.optString("mode") == mode.key && it.optInt("engine") == mode.engine &&
            it.optJSONArray("mix")?.length() == 3 && it.optInt("sign") in listOf(-1, 1)
    }.takeLast(30)
    if (ratings.isEmpty()) return base.copyOf()
    var state = seed.toUInt() xor 0x61c88647u
    var bestScore = Double.NEGATIVE_INFINITY
    var best = base.copyOf()
    repeat(18) {
        val candidate = base.copyOf()
        for (index in 0..2) {
            if (base[index] == 0.0) continue
            state = state * 1664525u + 1013904223u
            candidate[index] = (base[index] + 16 * (state.toDouble() / 4294967296.0 - .5))
                .roundToInt().coerceIn(0, 100).toDouble()
        }
        val score = ratings.withIndex().sumOf { (index, rating) ->
            val mix = rating.getJSONArray("mix")
            val distance = (0..2).sumOf { channel ->
                val diff = candidate[channel] - mix.optDouble(channel, base[channel])
                diff * diff
            }
            rating.optInt("sign") * exp(-distance / 480) * (.4 + .6 * (index + 1) / ratings.size)
        }
        if (score > bestScore) {
            bestScore = score
            best = candidate
        }
    }
    return best
}

internal fun sourcePosition(mode: Mode, elapsed: Double, intro: Int): JSONObject {
    val duration = intro * 60.0
    val reference = if (mode == Mode.Focus) elapsed + 22
        else if (elapsed <= 12) 38 + elapsed
        else if (elapsed < duration) 50 + 288 * (elapsed - 12) / (duration - 12)
        else 338 + elapsed - duration
    val (end, overlap, back) = if (mode == Mode.Focus) Triple(911, 10, 35) else Triple(2832, 8, 2690)
    val ref = if (reference < end) reference else back + overlap + (reference - end) % (end - back - overlap)
    val blend = ((ref - end + overlap) / overlap).coerceIn(0.0, 1.0)
    return JSONObject().put("ref", ref).put("second", back + blend * overlap).put("blend", blend)
}

internal object Native {
    init { System.loadLibrary("noizient") }
    external fun load(assets: AssetManager): Long
    external fun open(assets: Long, sleep: Boolean, seed: Long, intro: Int, values: DoubleArray, timer: Int): Long
    external fun close(handle: Long)
    external fun configure(handle: Long, values: DoubleArray)
    external fun pause(handle: Long, paused: Boolean)
    external fun timer(handle: Long, seconds: Int)
    external fun status(handle: Long): Int
    external fun events(handle: Long, output: DoubleArray): Int
}
