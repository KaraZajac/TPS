package org.soulstone.tps.nav

import java.util.Locale
import kotlin.math.abs

data class Waypoint(val id: Long, val label: String, val latitude: Double, val longitude: Double)

/**
 * Reads waypoints from text people paste or type, one per line: a label and a position,
 * in either order. Positions can be decimal degrees (`44.46044, -110.82814`, or with
 * hemisphere letters), degrees-minutes-seconds (`44°27'37.6"N 110°49'41.3"W`), or a
 * geohash (`9xchyjc2v`). Lines starting with `#` are comments.
 */
object WaypointText {

    data class Parsed(val label: String?, val latitude: Double, val longitude: Double)

    data class Result(val waypoints: List<Parsed>, val errors: List<String>)

    fun parse(text: String): Result {
        val waypoints = mutableListOf<Parsed>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parsed = parseLine(line)
            if (parsed == null) errors += "line ${i + 1}: no coordinates or geohash in “${line.take(40)}”"
            else waypoints += parsed
        }
        return Result(waypoints, errors)
    }

    fun parseLine(line: String): Parsed? = dms(line) ?: decimal(line) ?: geohash(line)

    // ------------------------------------------------------------ degrees, minutes, seconds

    private val DMS = Regex(
        """(\d{1,3}(?:\.\d+)?)\s*°\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′’]\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|''))?)?\s*([NnSsEeWw])"""
    )

    private fun dms(line: String): Parsed? {
        val parts = DMS.findAll(line).toList()
        for (i in 0 until parts.size - 1) {
            val a = parts[i]
            val b = parts[i + 1]
            if (!line.substring(a.range.last + 1, b.range.first).isSeparator()) continue
            val first = a.toDegrees()
            val second = b.toDegrees()
            val (lat, lon) = when {
                a.isLatitude() && !b.isLatitude() -> first to second
                !a.isLatitude() && b.isLatitude() -> second to first
                else -> continue
            }
            if (abs(lat) > 90 || abs(lon) > 180) continue
            return Parsed(label(line, a.range.first, b.range.last + 1), lat, lon)
        }
        return null
    }

    private fun MatchResult.isLatitude() = groupValues[4].uppercase() in setOf("N", "S")

    private fun MatchResult.toDegrees(): Double {
        val d = groupValues[1].toDouble() +
            (groupValues[2].toDoubleOrNull() ?: 0.0) / 60 +
            (groupValues[3].toDoubleOrNull() ?: 0.0) / 3600
        return if (groupValues[4].uppercase() in setOf("S", "W")) -d else d
    }

    // ------------------------------------------------------------ decimal degrees

    /** A number, optionally with a degree sign and a hemisphere letter. */
    private val NUMBER = Regex("""(?<![\w.])([-+]?\d{1,3}(?:\.\d+)?)\s*°?\s*([NnSsEeWw])?(?![\w.])""")

    private fun decimal(line: String): Parsed? {
        val numbers = NUMBER.findAll(line).toList()
        var best: Parsed? = null
        var bestScore = -1
        for (i in 0 until numbers.size - 1) {
            val a = numbers[i]
            val b = numbers[i + 1]
            if (!line.substring(a.range.last + 1, b.range.first).isSeparator()) continue
            val pair = decimalPair(a, b) ?: continue
            // Prefer pairs that look like coordinates (decimals or hemisphere letters) over
            // stray numbers in a label; among equals, the later pair.
            val score = listOf(a, b).count { '.' in it.groupValues[1] || it.groupValues[2].isNotEmpty() }
            if (score >= bestScore) {
                bestScore = score
                best = Parsed(label(line, a.range.first, b.range.last + 1), pair.first, pair.second)
            }
        }
        return best
    }

    private fun decimalPair(a: MatchResult, b: MatchResult): Pair<Double, Double>? {
        fun value(m: MatchResult): Double {
            val v = m.groupValues[1].toDouble()
            return when (m.groupValues[2].uppercase()) {
                "S", "W" -> -abs(v)
                "N", "E" -> abs(v)
                else -> v
            }
        }
        val ha = a.groupValues[2].uppercase()
        val hb = b.groupValues[2].uppercase()
        // Longitude first only when the letters say so.
        val swapped = (ha == "E" || ha == "W") && (hb.isEmpty() || hb == "N" || hb == "S")
        val (lat, lon) = if (swapped) value(b) to value(a) else value(a) to value(b)
        if ((ha == "N" || ha == "S") && (hb == "N" || hb == "S")) return null
        if ((ha == "E" || ha == "W") && (hb == "E" || hb == "W")) return null
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return lat to lon
    }

    // ------------------------------------------------------------ geohash

    private val TOKEN = Regex("""[0-9A-Za-z]+""")

    private fun geohash(line: String): Parsed? {
        val candidates = TOKEN.findAll(line)
            .filter { it.value.length in 5..12 && Geohash.isValid(it.value) && it.value.any(Char::isLetter) }
            .toList()
        // Real geohashes almost always contain a digit; a long all-letter one still counts,
        // but a short all-letter word in a label ("dense") doesn't.
        val pick = candidates.lastOrNull { t -> t.value.any(Char::isDigit) }
            ?: candidates.lastOrNull { it.value.length >= 7 }
            ?: return null
        val cell = Geohash.decode(pick.value) ?: return null
        return Parsed(label(line, pick.range.first, pick.range.last + 1), cell.latitude, cell.longitude)
    }

    // ------------------------------------------------------------ helpers

    private fun String.isSeparator() = all { it.isWhitespace() || it == ',' || it == ';' || it == '/' }

    /** What's left of the line once the position is cut out, without stray separators. */
    private fun label(line: String, start: Int, end: Int): String? {
        val rest = (line.substring(0, start) + " " + line.substring(end))
            .trim { it.isWhitespace() || it in ",;:|-–—()[]" }
            .replace(Regex("""\s+"""), " ")
        return rest.ifEmpty { null }
    }

    // ------------------------------------------------------------ storage and export

    /** Storage format: one waypoint per line, tab-separated id, label, latitude, longitude. */
    fun serialize(waypoints: List<Waypoint>): String = waypoints.joinToString("\n") {
        listOf(it.id.toString(), it.label.replace(Regex("""[\t\r\n]"""), " "), it.latitude.toString(), it.longitude.toString())
            .joinToString("\t")
    }

    fun deserialize(text: String): List<Waypoint> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 4) return@mapNotNull null
        val id = f[0].toLongOrNull() ?: return@mapNotNull null
        val lat = f[2].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[3].toDoubleOrNull() ?: return@mapNotNull null
        Waypoint(id, f[1], lat, lon)
    }

    /** One `label, latitude, longitude` line per waypoint, which [parse] reads back. */
    fun export(waypoints: List<Waypoint>): String = waypoints.joinToString("\n") {
        String.format(Locale.ROOT, "%s, %.6f, %.6f", it.label, it.latitude, it.longitude)
    }
}
