package sk.mirecky.skener.scanner

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Bod v normalizovaných súradniciach (0..1) vzpriameného obrazu. */
data class Pt(val x: Double, val y: Double) {
    fun dist(o: Pt): Double = hypot(x - o.x, y - o.y)
}

/**
 * Štvoruholník dokumentu s rohmi v poradí: ľavý horný, pravý horný, pravý dolný, ľavý dolný.
 * Súradnice sú normalizované na rozmery obrazu, v ktorom bol nájdený.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Plocha (shoelace) v jednotkách normalizovaného obrazu. */
    fun area(aspect: Double = 1.0): Double {
        val c = corners
        var s = 0.0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            s += a.x * aspect * b.y - b.x * aspect * a.y
        }
        return abs(s) / 2.0
    }

    /** Najväčší posun rohu oproti inému štvoruholníku (normalizované jednotky). */
    fun maxCornerShift(o: Quad): Double =
        corners.zip(o.corners).maxOf { (a, b) -> a.dist(b) }

    /**
     * Pomer dlhšej a kratšej strany odhadnutý z priemerov protiľahlých strán
     * (pri miernej perspektíve dostatočne presný odhad pomeru strán predlohy).
     * [aspect] je pomer šírky a výšky obrazu, aby sa vzdialenosti počítali v pixeloch.
     */
    fun sideRatio(aspect: Double): Double {
        fun d(a: Pt, b: Pt) = hypot((a.x - b.x) * aspect, a.y - b.y)
        val w = (d(tl, tr) + d(bl, br)) / 2
        val h = (d(tl, bl) + d(tr, br)) / 2
        return max(w, h) / maxOf(minOf(w, h), 1e-9)
    }

    /** Vnútorné uhly v stupňoch. */
    fun angles(aspect: Double): List<Double> {
        val c = corners.map { Pt(it.x * aspect, it.y) }
        return c.indices.map { i ->
            val p = c[i]
            val a = c[(i + 3) % 4]
            val b = c[(i + 1) % 4]
            val v1x = a.x - p.x; val v1y = a.y - p.y
            val v2x = b.x - p.x; val v2y = b.y - p.y
            val cos = (v1x * v2x + v1y * v2y) / (sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y) + 1e-12)
            Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
        }
    }

    fun isConvex(): Boolean {
        val c = corners
        var sign = 0
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    companion object {
        /** Zoradí ľubovoľné štyri body do poradia ľavý horný, pravý horný, pravý dolný, ľavý dolný. */
        fun fromUnordered(points: List<Pt>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            // Zoradenie podľa uhla okolo ťažiska (v smere hodinových ručičiek v obrazových súradniciach).
            val sorted = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            // Začiatok: bod s najmenším súčtom x + y (ľavý horný).
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}
