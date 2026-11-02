package org.soulstone.tps.nav

import kotlin.math.cos

/**
 * Geohash encode and decode (Gustavo Niemeyer's public-domain scheme): pure arithmetic,
 * so it works offline. Each character halves the cell five times, alternating
 * longitude and latitude, starting with longitude.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** The centre of a geohash cell, and its half-size in degrees. */
    data class Cell(val latitude: Double, val longitude: Double, val latitudeError: Double, val longitudeError: Double)

    fun encode(latitude: Double, longitude: Double, precision: Int = 9): String {
        require(precision in 1..12) { "precision must be 1..12" }
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        val out = StringBuilder(precision)
        var bits = 0
        var value = 0
        var lonBit = true
        while (out.length < precision) {
            if (lonBit) {
                val mid = (lonLo + lonHi) / 2
                if (longitude >= mid) { value = value * 2 + 1; lonLo = mid } else { value *= 2; lonHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (latitude >= mid) { value = value * 2 + 1; latLo = mid } else { value *= 2; latHi = mid }
            }
            lonBit = !lonBit
            if (++bits == 5) {
                out.append(BASE32[value])
                bits = 0
                value = 0
            }
        }
        return out.toString()
    }

    /** Decodes a geohash (any case). Null if it has a character geohashes don't use. */
    fun decode(hash: String): Cell? {
        if (!isValid(hash)) return null
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var lonBit = true
        for (c in hash.lowercase()) {
            val v = BASE32.indexOf(c)
            for (i in 4 downTo 0) {
                val bit = (v shr i) and 1
                if (lonBit) {
                    val mid = (lonLo + lonHi) / 2
                    if (bit == 1) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (bit == 1) latLo = mid else latHi = mid
                }
                lonBit = !lonBit
            }
        }
        return Cell((latLo + latHi) / 2, (lonLo + lonHi) / 2, (latHi - latLo) / 2, (lonHi - lonLo) / 2)
    }

    fun isValid(hash: String): Boolean =
        hash.length in 1..12 && hash.lowercase().all { it in BASE32 }

    /** A cell's size in metres at [latitude]: width (east–west) and height (north–south). */
    fun cellSizeMeters(precision: Int, latitude: Double): Pair<Double, Double> {
        val lonBits = (5 * precision + 1) / 2
        val latBits = 5 * precision / 2
        val metresPerDegree = 111_320.0
        val width = 360.0 / (1L shl lonBits) * metresPerDegree * cos(Math.toRadians(latitude))
        val height = 180.0 / (1L shl latBits) * metresPerDegree
        return width to height
    }
}
