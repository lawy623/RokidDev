package com.rokid.music.model

import org.json.JSONArray
import org.json.JSONObject

// ── Shared Helpers ─────────────────────────────────────────────────────────

internal inline fun <T> parseArray(arr: JSONArray?, parser: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { parser(arr.getJSONObject(it)) }
}

// ── Top Level ──────────────────────────────────────────────────────────────

data class TabScore(
    val metadata: ScoreMetadata,
    val defaults: ScoreDefaults,
    val tracks: List<Track>,
    val systems: List<SystemGroup>,
    val measures: List<Measure>
) {
    companion object {
        fun parse(json: String): TabScore {
            val root = JSONObject(json)
            return TabScore(
                metadata = ScoreMetadata.parse(root.optJSONObject("metadata")),
                defaults = ScoreDefaults.parse(root.optJSONObject("defaults")),
                tracks = parseArray(root.optJSONArray("tracks")) { Track.parse(it) },
                systems = parseArray(root.optJSONArray("systems")) { SystemGroup.parse(it) },
                measures = parseArray(root.optJSONArray("measures")) { Measure.parse(it) }
            )
        }
    }
}

// ── Metadata ───────────────────────────────────────────────────────────────

data class ScoreMetadata(
    val title: String,
    val artist: String
) {
    companion object {
        fun parse(obj: JSONObject?) = ScoreMetadata(
            title = obj?.optString("title", "Unknown") ?: "Unknown",
            artist = obj?.optString("artist", "") ?: ""
        )
    }
}

// ── Defaults ───────────────────────────────────────────────────────────────

data class ScoreDefaults(
    val ppq: Int,
    val tempo: Tempo,
    val timeSignature: TimeSig?,
    val tuning: Tuning
) {
    companion object {
        fun parse(obj: JSONObject?) = ScoreDefaults(
            ppq = obj?.optInt("ppq", 960) ?: 960,
            tempo = Tempo.parse(obj?.optJSONObject("tempo")),
            timeSignature = obj?.let { TimeSig.parse(it.optJSONObject("timeSignature")) },
            tuning = Tuning.parse(obj?.optJSONObject("tuning"))
        )
    }
}

data class Tempo(
    val bpm: Int,
    val beatUnit: Int
) {
    companion object {
        fun parse(obj: JSONObject?) = Tempo(
            bpm = obj?.optInt("bpm", 75) ?: 75,
            beatUnit = obj?.optInt("beatUnit", 4) ?: 4
        )
    }
}

data class TimeSig(
    val beats: Int,
    val beatType: Int
) {
    companion object {
        fun parse(obj: JSONObject?) = TimeSig(
            beats = obj?.optInt("beats", 4) ?: 4,
            beatType = obj?.optInt("beatType", 4) ?: 4
        )
    }
}

data class Tuning(
    val name: String,
    val capo: Int,
    val strings: List<TuningString>
) {
    companion object {
        fun parse(obj: JSONObject?) = Tuning(
            name = obj?.optString("name", "Standard") ?: "Standard",
            capo = obj?.optInt("capo", 0) ?: 0,
            strings = parseArray(obj?.optJSONArray("strings")) { TuningString.parse(it) }
        )
    }
}

data class TuningString(
    val number: Int,
    val pitch: String
) {
    companion object {
        fun parse(obj: JSONObject) = TuningString(
            number = obj.optInt("number", 0),
            pitch = obj.optString("pitch", "E4")
        )
    }
}

// ── Track ──────────────────────────────────────────────────────────────────

data class Track(
    val id: String,
    val name: String,
    val instrument: String,
    val midiProgram: Int,
    val stringCount: Int
) {
    companion object {
        fun parse(obj: JSONObject) = Track(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            instrument = obj.optString("instrument", ""),
            midiProgram = obj.optInt("midiProgram", 30),
            stringCount = obj.optInt("stringCount", 6)
        )
    }
}

// ── System (measure grouping) ──────────────────────────────────────────────

data class SystemGroup(
    val id: String,
    val measureIds: List<String>
) {
    companion object {
        fun parse(obj: JSONObject): SystemGroup {
            val ids = mutableListOf<String>()
            val arr = obj.optJSONArray("measureIds")
            if (arr != null) {
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
            }
            return SystemGroup(id = obj.optString("id", ""), measureIds = ids)
        }
    }
}

// ── Measure ────────────────────────────────────────────────────────────────

data class Measure(
    val id: String,
    val number: Int,
    val trackId: String,
    val startTick: Int,
    val durationTicks: Int,
    val timeSignature: TimeSig?,
    val barline: Barline?,
    val events: List<Event>,
    val spanners: List<Spanner>,
    // _showTimeSig: assigned during layout, not from JSON
    var showTimeSig: Boolean = false,
    // _wide: editor flag, may exist in JSON
    var wide: Boolean = false
) {
    companion object {
        fun parse(obj: JSONObject): Measure {
            return Measure(
                id = obj.optString("id", ""),
                number = obj.optInt("number", 1),
                trackId = obj.optString("trackId", ""),
                startTick = obj.optInt("startTick", 0),
                durationTicks = obj.optInt("durationTicks", 3840),
                timeSignature = obj.optJSONObject("_timeSig")?.let { TimeSig.parse(it) },
                barline = Barline.parse(obj.optJSONObject("barline")),
                events = parseArray(obj.optJSONArray("events")) { Event.parse(it) },
                spanners = parseArray(obj.optJSONArray("spanners")) { Spanner.parse(it) },
                showTimeSig = obj.optBoolean("_showTimeSig", false),
                wide = obj.optBoolean("_wide", false)
            )
        }
    }
}

data class Barline(
    val left: String,
    val right: String
) {
    companion object {
        fun parse(obj: JSONObject?) = Barline(
            left = obj?.optString("left", "single") ?: "single",
            right = obj?.optString("right", "single") ?: "single"
        )
    }
}

// ── Event ──────────────────────────────────────────────────────────────────

data class Event(
    val id: String,
    val type: String,       // "note" | "rest"
    val tick: Int,
    val duration: Duration,
    val voice: Int,
    val beamGroup: String?,
    val notes: List<Note>,
    val articulations: List<String>
) {
    companion object {
        fun parse(obj: JSONObject): Event {
            val arts = mutableListOf<String>()
            val artArr = obj.optJSONArray("articulations")
            if (artArr != null) {
                for (i in 0 until artArr.length()) {
                    val a = artArr.opt(i)
                    when (a) {
                        is String -> arts.add(a)
                        is JSONObject -> arts.add(a.optString("type", ""))
                    }
                }
            }
            return Event(
                id = obj.optString("id", ""),
                type = obj.optString("type", "note"),
                tick = obj.optInt("tick", 0),
                duration = Duration.parse(obj.optJSONObject("duration")),
                voice = obj.optInt("voice", 1),
                beamGroup = if (obj.has("beamGroup")) obj.optString("beamGroup") else null,
                notes = parseArray(obj.optJSONArray("notes")) { Note.parse(it) },
                articulations = arts
            )
        }
    }
}

data class Duration(
    val base: Int,
    val dots: Int,
    val tuplet: Tuplet?
) {
    companion object {
        fun parse(obj: JSONObject?) = Duration(
            base = obj?.optInt("base", 4) ?: 4,
            dots = obj?.optInt("dots", 0) ?: 0,
            tuplet = Tuplet.parse(obj?.optJSONObject("tuplet"))
        )
    }
}

data class Tuplet(
    val actual: Int,
    val normal: Int
) {
    companion object {
        fun parse(obj: JSONObject?) = if (obj == null) null else Tuplet(
            actual = obj.optInt("actual", 3),
            normal = obj.optInt("normal", 2)
        )
    }
}

data class Note(
    val id: String,
    val string: Int,          // 1-indexed, 1=highest
    val fret: Int,
    val display: String,
    val status: String,       // normal | tied | ring | dead | ghost | mute
    val effects: List<Effect>
) {
    companion object {
        fun parse(obj: JSONObject): Note {
            val fx = mutableListOf<Effect>()
            val fxArr = obj.optJSONArray("effects")
            if (fxArr != null) {
                for (i in 0 until fxArr.length()) {
                    fx.add(Effect.parse(fxArr.getJSONObject(i)))
                }
            }
            return Note(
                id = obj.optString("id", ""),
                string = obj.optInt("string", 1),
                fret = obj.optInt("fret", 0),
                display = obj.optString("display", obj.optString("fret", "0")),
                status = obj.optString("status", "normal"),
                effects = fx
            )
        }
    }
}

data class Effect(
    val type: String,
    val label: String?,
    val kind: String?,
    val to: String?,
    val toEvent: String?
) {
    companion object {
        fun parse(obj: JSONObject) = Effect(
            type = obj.optString("type", ""),
            label = if (obj.has("label")) obj.optString("label") else null,
            kind = if (obj.has("kind")) obj.optString("kind") else null,
            to = if (obj.has("to")) obj.optString("to") else null,
            toEvent = if (obj.has("toEvent")) obj.optString("toEvent") else null
        )
    }
}

// ── Spanner (techniques) ───────────────────────────────────────────────────

data class Spanner(
    val id: String,
    val type: String,
    val from: String?,        // note id
    val to: String?,          // note id
    val fromEvent: String?,
    val toEvent: String?,
    val label: String?,
    val direction: String?,
    val curve: List<CurvePoint>,
    val width: String?,
    val fromFret: Int?,
    val toFret: Int?,
    val placement: String?
) {
    companion object {
        fun parse(obj: JSONObject): Spanner {
            return Spanner(
                id = obj.optString("id", ""),
                type = obj.optString("type", ""),
                from = if (obj.has("from")) obj.optString("from") else null,
                to = if (obj.has("to")) obj.optString("to") else null,
                fromEvent = if (obj.has("fromEvent")) obj.optString("fromEvent") else null,
                toEvent = if (obj.has("toEvent")) obj.optString("toEvent") else null,
                label = if (obj.has("label")) obj.optString("label") else null,
                direction = if (obj.has("direction")) obj.optString("direction") else null,
                curve = CurvePoint.parseArray(obj.optJSONArray("curve")),
                width = if (obj.has("width")) obj.optString("width") else null,
                fromFret = if (obj.has("fromFret")) obj.optInt("fromFret") else null,
                toFret = if (obj.has("toFret")) obj.optInt("toFret") else null,
                placement = if (obj.has("placement")) obj.optString("placement") else null
            )
        }
    }
}

data class CurvePoint(
    val at: Double,
    val alter: Double
) {
    companion object {
        fun parseArray(arr: JSONArray?): List<CurvePoint> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { index ->
                arr.optJSONObject(index)?.let { obj ->
                    CurvePoint(
                        at = obj.optDouble("at", 0.0),
                        alter = obj.optDouble("alter", 0.0)
                    )
                }
            }
        }
    }
}
