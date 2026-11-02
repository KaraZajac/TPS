package org.soulstone.tps.gnss

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Dilution of precision: how much the satellites' geometry magnifies range errors. */
data class Dop(val pdop: Double, val hdop: Double, val vdop: Double)

object DopCalculator {

    /**
     * DOP from the geometry of the satellites used in the fix, for phones that send no
     * NMEA `GSA` sentence.
     *
     * Each system gets its own receiver-clock term, the way a multi-GNSS receiver
     * solves for the bias between systems. That is why a lone satellite from one
     * system adds nothing to the geometry. The chip's own figure can differ slightly
     * because it weights satellites by signal quality. Null when the geometry can't be
     * solved.
     */
    fun compute(satellites: List<Satellite>): Dop? {
        val used = satellites.filter { it.state == SatState.USED && it.plottable }
        val systems = used.map { it.constellation }.distinct()
        val n = 3 + systems.size
        if (used.size < n) return null

        val normal = Array(n) { DoubleArray(n) }
        val row = DoubleArray(n)
        for (s in used) {
            val el = Math.toRadians(s.elevationDeg.toDouble())
            val az = Math.toRadians(s.azimuthDeg.toDouble())
            row.fill(0.0)
            // Unit vector from the satellite towards the receiver, in east/north/up.
            row[0] = -cos(el) * sin(az)
            row[1] = -cos(el) * cos(az)
            row[2] = -sin(el)
            row[3 + systems.indexOf(s.constellation)] = 1.0
            for (i in 0 until n) for (j in 0 until n) normal[i][j] += row[i] * row[j]
        }
        val q = invert(normal) ?: return null
        val east = q[0][0]
        val north = q[1][1]
        val up = q[2][2]
        if (east < 0 || north < 0 || up < 0) return null
        return Dop(pdop = sqrt(east + north + up), hdop = sqrt(east + north), vdop = sqrt(up))
    }

    /** Gauss–Jordan inverse with partial pivoting; null if (nearly) singular. */
    internal fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) matrix[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-9) return null
            if (pivot != col) a[pivot] = a[col].also { a[col] = a[pivot] }
            val p = a[col][col]
            for (j in 0 until 2 * n) a[col][j] /= p
            for (i in 0 until n) {
                if (i == col) continue
                val f = a[i][col]
                if (f == 0.0) continue
                for (j in 0 until 2 * n) a[i][j] -= f * a[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][n + j] } }
    }
}
