package sk.mirecky.skener.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Úsečka z Houghovej transformácie (súradnice v pixeloch analyzovaného obrazu). */
private class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val len = kotlin.math.hypot(x2 - x1, y2 - y1)
    val horizontal = kotlin.math.abs(y2 - y1) < kotlin.math.abs(x2 - x1) * 0.45
    val vertical = kotlin.math.abs(x2 - x1) < kotlin.math.abs(y2 - y1) * 0.45
}

/** Výsledok detekcie v jednom snímku analýzy. */
data class Detection(
    val quad: Quad,
    /** Najmenší kontrast (rozdiel jasu vnútri a vonku) spomedzi štyroch strán. */
    val contrast: Double,
    /** Podiel plochy záberu, ktorý dokument zaberá (0..1). */
    val areaFraction: Double,
    /** Ostrosť: rozptyl Laplaceovho operátora vo vnútri dokumentu (závisí od množstva textu). */
    val sharpness: Double,
    /**
     * Ostrosť nezávislá od množstva textu: pomer [sharpness] k rozptylu po ďalšom rozmazaní obrazu.
     * Ostrý obraz ďalšie rozmazanie výrazne zmení (vysoký pomer), rozmazaný takmer vôbec.
     */
    val sharpRatio: Double,
)

/** Hraničné hodnoty pre overenie, že nájdený štvoruholník je skutočne dokument (F2). */
data class DetectionCriteria(
    val minArea: Double,
    val maxArea: Double,
    val minSideRatio: Double,
    val maxSideRatio: Double,
    /**
     * Prijímať len dokumenty orientované na výšku. List A4 skenovaný telefónom na výšku leží
     * takmer vždy na výšku; obrazovky, televízory a obrazy bývajú na šírku (ochrana pred falošnými nálezmi).
     */
    val portraitOnly: Boolean,
    val minAngle: Double,
    val maxAngle: Double,
    /** Minimálny rozdiel jasu vnútri a vonku pozdĺž každej strany (medián vzoriek). */
    val minSideContrast: Double,
    /** Či musí byť vnútro svetlejšie než okolie (papier); pri tabuli stačí absolútny rozdiel. */
    val insideBrighter: Boolean,
    /** Uprednostniť kandidátov s pomerom strán A4. */
    val preferA4: Boolean,
    /** Minimálny priemerný jas vnútri (papier je svetlý); 0 = nekontroluje sa. */
    val minInsideBrightness: Double,
    /**
     * Pomer strán, pri ktorom sa prijme aj list na šírku (napr. telefón v stojane otočený bokom).
     * Úzky rozsah okolo √2 (A formáty) vylúči obrazovky 16:9 a 16:10.
     */
    val landscapeRatio: ClosedFloatingPointRange<Double>? = null,
) {
    companion object {
        fun forMode(mode: ScanMode) = when (mode) {
            ScanMode.DOCUMENT -> DetectionCriteria(
                // Formáty A4, A5 aj A6 majú rovnaký pomer strán (√2), stačí jedno kritérium.
                // 4 % záberu: list A6 snímaný z 25–30 cm (bližšie hlavný fotoaparát nezaostrí).
                // Uhly, kontrast hrán a jas papiera sú blízko režimu Tabuľa: malý list s ručným
                // písmom pri slabšom svetle mal papier tmavší a hrany menej kontrastné.
                // Pomer strán od 1,0: list fotený zošikma (do ~45°) sa v obraze skráti; na výšku
                // musí ostať, čo spolu so svetlým vnútrom odmieta obrazovky a iné predmety.
                minArea = 0.04, maxArea = 0.97, minSideRatio = 1.0, maxSideRatio = 1.80, portraitOnly = true,
                minAngle = 45.0, maxAngle = 135.0,
                minSideContrast = 8.0, insideBrighter = true, preferA4 = true,
                minInsideBrightness = 85.0,
                landscapeRatio = 1.27..1.56,
            )
            ScanMode.WHITEBOARD -> DetectionCriteria(
                minArea = 0.20, maxArea = 0.98, minSideRatio = 1.0, maxSideRatio = 3.0, portraitOnly = false,
                minAngle = 45.0, maxAngle = 135.0,
                minSideContrast = 10.0, insideBrighter = false, preferA4 = false,
                minInsideBrightness = 80.0,
            )
        }
    }
}

/**
 * Hľadá dokument v sivotónovom, vzpriamenom obraze nízkeho rozlíšenia (cca 480 × 640).
 *
 * Postup: rozmazanie → Cannyho hrany a Otsuovo prahovanie (dva nezávislé zdroje kandidátov) →
 * kontúry → konvexný obal → aproximácia štvoruholníkom → overenie podľa [DetectionCriteria].
 * Z platných kandidátov sa vyberie ten s najväčšou plochou váženou podporou hrán.
 *
 * Trieda nie je vláknovo bezpečná (opakovane používa pracovné matice).
 */
class DocumentDetector {

    /** Voliteľný záznam dôvodov zamietnutia kandidátov (na ladenie a testy). */
    var log: ((String) -> Unit)? = null

    /** Pri poslednej detekcii bol zamietnutý tvar dokumentu len preto, že presahuje okraj záberu. */
    var outOfFrame = false
        private set

    private val blurred = Mat()
    private val edges = Mat()
    private val binary = Mat()
    private val reblurred = Mat()
    private val lap = Mat()
    private val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    private val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

    fun detect(gray: Mat, mode: ScanMode): Detection? {
        val criteria = DetectionCriteria.forMode(mode)
        outOfFrame = false
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        // Citlivejší druhý prechod (nižšie prahy Cannyho detektora) v testoch zvyšoval počet falošných
        // nálezov viac, než pomáhal pri bielom papieri na bielom stole; ten prípad rieši až model (F1).
        return pass(gray, criteria, CANNY_LOW, CANNY_HIGH, useOtsu = true)
    }

    private fun pass(gray: Mat, criteria: DetectionCriteria, low: Double, high: Double, useOtsu: Boolean): Detection? {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()

        Imgproc.Canny(blurred, edges, low, high)
        Imgproc.dilate(edges, edges, kernel3)

        val candidates = ArrayList<MatOfPoint>()
        val minContour = criteria.minArea * 0.75
        candidates += contours(edges, minContour)
        if (useOtsu) {
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel5)
            candidates += contours(binary, minContour)
        }

        var best: Detection? = null
        var bestScore = 0.0
        val quads = candidates.flatMap { toQuads(it, w, h, criteria) }.toMutableList()
        // Tabuľa: kandidáti aj z rovných čiar rámu (obrys často „uteká“ po kábli či tieni)
        // a obrysy so stranou dorovnanou na rovnobežnú čiaru rámu.
        if (!criteria.insideBrighter) {
            computeSegments(w, h)
            quads += quads.mapNotNull { q -> refineSides(q, w, h)?.let { validate(it, w, h, criteria) } }
            quads += lineCandidates(w, h, criteria)
        }
        run {
          for (quad in quads) {
            val detection = evaluate(quad, gray, w, h, criteria) ?: continue
            // Pri dokumente majú prednosť tvary s pomerom strán blízkym A4.
            val ratioPenalty = if (criteria.preferA4) {
                1.0 - minOf(kotlin.math.abs(quad.sideRatio(w / h) - ImageOps.A4_RATIO) / ImageOps.A4_RATIO, 0.5)
            } else {
                1.0
            }
            // Tabuľa sa fotí spredu: protiľahlé strany sú takmer rovnobežné. Tvar so stranou
            // „ujdenou“ po kábli alebo tieni (výrazne nerovnobežnou) dostane nižšie skóre.
            val parallel = if (criteria.preferA4) 1.0 else parallelism(quad, w / h)
            val score = detection.areaFraction * minOf(detection.contrast, 60.0) * ratioPenalty * parallel
            if (score > bestScore) {
                bestScore = score
                best = detection
            }
          }
        }
        candidates.forEach { it.release() }
        return best
    }

    private fun contours(src: Mat, minFraction: Double): List<MatOfPoint> {
        val list = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(src, list, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = src.rows() * src.cols() * minFraction
        val (keep, drop) = list.partition { Imgproc.contourArea(it) >= minArea }
        drop.forEach { it.release() }
        return keep
    }

    /**
     * Kandidáti na štvoruholník z kontúry: priama aproximácia štyrmi bodmi a rekonštrukcia
     * zo štyroch najdlhších hrán (list so zahnutým alebo odtrhnutým rohom má päť a viac vrcholov;
     * skrytý roh je priesečník predĺžených susedných hrán). Vráti len tvary, ktoré spĺňajú kritériá.
     */
    private fun toQuads(contour: MatOfPoint, w: Double, h: Double, c: DetectionCriteria): List<Quad> {
        val out = ArrayList<Quad>(2)
        toQuad(contour, w, h, c)?.let { out += it }
        fromLongEdges(contour, w, h)?.let { q -> validate(q, w, h, c)?.let { out += it } }
        return out
    }

    /** 1 pre rovnobežné protiľahlé strany, menej pri rozdiele smerov (Gaussovsky, 15°). */
    private fun parallelism(q: Quad, aspect: Double): Double {
        val c = q.corners.map { Pair(it.x * aspect, it.y) }
        fun dir(a: Int, b: Int) = kotlin.math.atan2(c[b].second - c[a].second, c[b].first - c[a].first)
        fun diff(a: Double, b: Double): Double {
            var d = Math.toDegrees(kotlin.math.abs(a - b)) % 180.0
            if (d > 90) d = 180 - d
            return d
        }
        val top = dir(0, 1); val bottom = dir(3, 2); val left = dir(0, 3); val right = dir(1, 2)
        val worst = maxOf(diff(top, bottom), diff(left, right))
        return kotlin.math.exp(-(worst / 15.0) * (worst / 15.0))
    }

    /**
     * Kandidáti z rovných čiar (Houghova transformácia): najdlhšie takmer vodorovné a takmer zvislé
     * čiary, ich dvojice a priesečníky. Prejdú len tvary s hranou pozdĺž všetkých štyroch strán.
     */
    private fun lineCandidates(w: Double, h: Double, c: DetectionCriteria): List<Quad> {
        val segs = segments
        fun Seg.mid(): Double = if (horizontal) y1 + (y2 - y1) * ((w / 2 - x1) / (x2 - x1)) else x1 + (x2 - x1) * ((h / 2 - y1) / (y2 - y1))
        // Zlúčenie takmer totožných čiar; z každej skupiny ostane najdlhší úsek.
        fun pick(list: List<Seg>): List<Seg> {
            val out = ArrayList<Seg>()
            for (s in list.sortedByDescending { it.len }) {
                if (out.none { kotlin.math.abs(it.mid() - s.mid()) < 8 }) out += s
                if (out.size == 6) break
            }
            return out
        }
        return lineQuads(pick(segs.filter { it.horizontal }), pick(segs.filter { it.vertical }), w, h, c)
    }

    /** Úsečky z Houghovej transformácie hrán aktuálneho snímku (len režim Tabuľa). */
    private var segments: List<Seg> = emptyList()

    private fun computeSegments(w: Double, h: Double) {
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 60, minOf(w, h) * 0.3, 10.0)
        val segs = ArrayList<Seg>()
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0)
            segs += Seg(v[0], v[1], v[2], v[3])
        }
        lines.release()
        segments = segs
    }

    /** Štvoruholníky z dvojíc vodorovných a zvislých čiar; prejdú len tvary s hranou na všetkých stranách. */
    private fun lineQuads(hs: List<Seg>, vs: List<Seg>, w: Double, h: Double, c: DetectionCriteria): List<Quad> {
        fun Seg.mid(): Double = if (horizontal) y1 + (y2 - y1) * ((w / 2 - x1) / (x2 - x1)) else x1 + (x2 - x1) * ((h / 2 - y1) / (y2 - y1))
        val out = ArrayList<Pair<Quad, Double>>()
        for (i in hs.indices) for (j in i + 1 until hs.size) {
            val (t, b) = if (hs[i].mid() < hs[j].mid()) hs[i] to hs[j] else hs[j] to hs[i]
            if (b.mid() - t.mid() < 0.25 * h) continue
            for (k in vs.indices) for (m in k + 1 until vs.size) {
                val (l, r) = if (vs[k].mid() < vs[m].mid()) vs[k] to vs[m] else vs[m] to vs[k]
                if (r.mid() - l.mid() < 0.25 * w) continue
                fun x(a: Seg, b: Seg) = intersect(Point(a.x1, a.y1), Point(a.x2, a.y2), Point(b.x1, b.y1), Point(b.x2, b.y2))
                val pts = listOf(x(t, l), x(t, r), x(b, r), x(b, l))
                if (pts.any { it == null || it.x < -0.05 * w || it.x > 1.05 * w || it.y < -0.05 * h || it.y > 1.05 * h }) continue
                val quad = validate(Quad.fromUnordered(pts.map { Pt(it!!.x / w, it.y / h) }), w, h, c) ?: continue
                val support = minSideLineSupport(quad, w, h)
                if (support >= MIN_LINE_SUPPORT) out += quad to quad.area() * support
            }
        }
        return out.sortedByDescending { it.second }.take(3).map { it.first }
    }

    /**
     * Strana obrysu, ktorá nie je rovnobežná s protiľahlou (obrys „ušiel“ po kábli), sa nahradí
     * najbližšou rovnou čiarou vo vnútri, ktorá s protiľahlou stranou rovnobežná je (rám tabule).
     * Vráti upravený tvar alebo null, ak nie je čo dorovnať.
     */
    private fun refineSides(q: Quad, w: Double, h: Double): Quad? {
        val c = q.corners.map { Point(it.x * w, it.y * h) }  // tl, tr, br, bl
        // Strany ako dvojice bodov: hore, vpravo, dole, vľavo; protiľahlá strana má index (i + 2) % 4.
        val sides = arrayOf(c[0] to c[1], c[1] to c[2], c[3] to c[2], c[0] to c[3])
        fun angle(a: Point, b: Point) = Math.toDegrees(kotlin.math.atan2(b.y - a.y, b.x - a.x))
        fun diff(a: Double, b: Double): Double { var d = kotlin.math.abs(a - b) % 180.0; if (d > 90) d = 180 - d; return d }
        fun dist(p: Point, a: Point, b: Point): Double {
            val l = kotlin.math.hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-6)
            return kotlin.math.abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / l
        }
        val poly = MatOfPoint2f(*c.toTypedArray())
        var changed = false
        val lines = sides.copyOf()
        for (i in 0 until 4) {
            val (a, b) = sides[i]
            val (oa, ob) = sides[(i + 2) % 4]
            val oppAngle = angle(oa, ob)
            if (diff(angle(a, b), oppAngle) <= REFINE_MIN_SKEW) continue
            val sideLen = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val span = dist(Point((oa.x + ob.x) / 2, (oa.y + ob.y) / 2), a, b)
            val horizontal = i == 0 || i == 2
            val best = segments
                .filter { if (horizontal) it.horizontal else it.vertical }
                .filter { diff(Math.toDegrees(kotlin.math.atan2(it.y2 - it.y1, it.x2 - it.x1)), oppAngle) <= REFINE_PARALLEL }
                .filter { it.len >= 0.4 * sideLen }
                .filter { Imgproc.pointPolygonTest(poly, Point((it.x1 + it.x2) / 2, (it.y1 + it.y2) / 2), false) >= 0 }
                .map { it to dist(Point((it.x1 + it.x2) / 2, (it.y1 + it.y2) / 2), a, b) }
                .filter { it.second <= 0.25 * span }
                .minByOrNull { it.second }?.first ?: continue
            lines[i] = Point(best.x1, best.y1) to Point(best.x2, best.y2)
            changed = true
        }
        poly.release()
        if (!changed) return null
        fun x(i: Int, j: Int) = intersect(lines[i].first, lines[i].second, lines[j].first, lines[j].second)
        val pts = listOf(x(0, 3), x(0, 1), x(2, 1), x(2, 3))
        if (pts.any { it == null }) return null
        return Quad.fromUnordered(pts.map { Pt(it!!.x / w, it.y / h) })
    }

    /** Štvoruholník zo štyroch najdlhších hrán konvexného obalu (pozri [toQuads]). */
    private fun fromLongEdges(contour: MatOfPoint, w: Double, h: Double): Quad? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val all = contour.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { all[it] }.toTypedArray())
        hullIdx.release()
        val peri = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(hull, approx, 0.012 * peri, true)
        val p = approx.toArray()
        approx.release()
        hull.release()
        if (p.size !in 5..10) return null
        val n = p.size
        val edges = (0 until n).map { i -> i to kotlin.math.hypot(p[(i + 1) % n].x - p[i].x, p[(i + 1) % n].y - p[i].y) }
        val longest = edges.sortedByDescending { it.second }.take(4)
        // Štyri strany listu musia tvoriť väčšinu obvodu (inak nejde o list s poškodeným rohom).
        if (longest.sumOf { it.second } < 0.75 * peri) return null
        val chosen = longest.map { it.first }.sorted()
        val corners = ArrayList<Point>(4)
        for (k in 0 until 4) {
            val a = chosen[k]
            val b = chosen[(k + 1) % 4]
            val pt = intersect(p[a], p[(a + 1) % n], p[b], p[(b + 1) % n]) ?: return null
            // Rekonštruovaný roh nesmie ležať ďaleko mimo záberu.
            if (pt.x < -0.1 * w || pt.x > 1.1 * w || pt.y < -0.1 * h || pt.y > 1.1 * h) return null
            corners += pt
        }
        return Quad.fromUnordered(corners.map { Pt(it.x / w, it.y / h) })
    }

    /** Priesečník priamok AB a CD, alebo null, ak sú takmer rovnobežné. */
    private fun intersect(a: Point, b: Point, c: Point, d: Point): Point? {
        val d1x = b.x - a.x; val d1y = b.y - a.y
        val d2x = d.x - c.x; val d2y = d.y - c.y
        val den = d1x * d2y - d1y * d2x
        if (kotlin.math.abs(den) < 1e-6 * kotlin.math.hypot(d1x, d1y) * kotlin.math.hypot(d2x, d2y)) return null
        val t = ((c.x - a.x) * d2y - (c.y - a.y) * d2x) / den
        return Point(a.x + t * d1x, a.y + t * d1y)
    }

    /** Aproximuje kontúru štvoruholníkom; vráti null, ak to nejde alebo tvar nespĺňa geometrické kritériá. */
    private fun toQuad(contour: MatOfPoint, w: Double, h: Double, c: DetectionCriteria): Quad? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val pts = contour.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        hullIdx.release()
        val peri = Imgproc.arcLength(hull, true)
        var result: Quad? = null
        for (eps in EPSILONS) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, eps * peri, true)
            val p = approx.toArray()
            approx.release()
            if (p.size == 4) {
                result = Quad.fromUnordered(p.map { Pt(it.x / w, it.y / h) })
                break
            }
            if (p.size < 4) break
        }
        hull.release()
        if (result == null) {
            log?.invoke("kontúra sa nedá aproximovať štvoruholníkom")
            return null
        }

        return validate(result, w, h, c)
    }

    /** Geometrické kritériá (konvexnosť, plocha, pomer strán, orientácia, uhly). */
    private fun validate(result: Quad, w: Double, h: Double, c: DetectionCriteria): Quad? {
        val aspect = w / h
        val area = result.area()
        val reason = when {
            !result.isConvex() -> "nekonvexný"
            area < c.minArea || area > c.maxArea -> "plocha %.2f".format(area)
            result.sideRatio(aspect) !in c.minSideRatio..c.maxSideRatio -> "pomer strán %.2f".format(result.sideRatio(aspect))
            c.portraitOnly && !result.isPortrait(aspect) && c.landscapeRatio?.contains(result.sideRatio(aspect)) != true -> "na šírku (pomer %.2f)".format(result.sideRatio(aspect))
            result.angles(aspect).any { it < c.minAngle || it > c.maxAngle } -> "uhly ${result.angles(aspect).map { it.toInt() }}"
            else -> null
        }
        if (reason != null) {
            log?.invoke("zamietnutý $result: $reason")
            return null
        }
        return result
    }

    private fun evaluate(quad: Quad, gray: Mat, w: Double, h: Double, c: DetectionCriteria): Detection? {
        val contrast = minSideContrast(quad, w, h, c.insideBrighter)
        if (contrast == null) {
            outOfFrame = true
            log?.invoke("zamietnutý $quad: presahuje záber")
            return null
        }
        // Tabuľa na bielej stene nemá rozdiel jasu vnútri a vonku; jej hranu tvorí rám (tenká línia).
        // Ako náhradný dôkaz hrany stačí ostrý prechod jasu pozdĺž väčšiny každej strany.
        val support = if (!c.insideBrighter && contrast < c.minSideContrast) minSideLineSupport(quad, w, h) else 0.0
        if (contrast < c.minSideContrast && support < MIN_LINE_SUPPORT) {
            log?.invoke("zamietnutý $quad: kontrast strany %.1f, línia hrany %.2f".format(contrast, support))
            return null
        }
        val effectiveContrast = if (contrast >= c.minSideContrast) contrast else LINE_CONTRAST * support

        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val poly = MatOfPoint(*quad.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
        poly.release()
        // Zmenšenie masky, aby do merania nezasahoval okraj a pozadie.
        Imgproc.erode(mask, mask, kernel5, Point(-1.0, -1.0), 3)

        val inside = Core.mean(gray, mask).`val`[0]
        if (inside < c.minInsideBrightness) {
            log?.invoke("zamietnutý $quad: jas vnútri %.0f".format(inside))
            mask.release()
            return null
        }

        val sharpness = laplacianVariance(gray, mask)
        Imgproc.GaussianBlur(gray, reblurred, Size(0.0, 0.0), REBLUR_SIGMA)
        val sharpRatio = sharpness / laplacianVariance(reblurred, mask).coerceAtLeast(1e-3)
        mask.release()

        return Detection(quad, effectiveContrast, quad.area(), sharpness, sharpRatio)
    }

    private fun laplacianVariance(src: Mat, mask: Mat): Double {
        Imgproc.Laplacian(src, lap, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        Core.meanStdDev(lap, mean, std, mask)
        val v = std.toArray()[0].let { it * it }
        mean.release(); std.release()
        return v
    }

    /**
     * Pre každú stranu podiel vzoriek, pri ktorých naprieč stranou (±[LINE_RANGE] px) leží ostrý
     * prechod jasu – rám tabule alebo hrana predmetu. Vráti najmenší podiel spomedzi štyroch strán.
     */
    private fun minSideLineSupport(quad: Quad, w: Double, h: Double): Double {
        val c = quad.corners.map { Pair(it.x * w, it.y * h) }
        val maxX = blurred.cols() - 1
        val maxY = blurred.rows() - 1
        var worst = 1.0
        for (i in 0 until 4) {
            val (ax, ay) = c[i]
            val (bx, by) = c[(i + 1) % 4]
            val len = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
            val nx = -(by - ay) / len
            val ny = (bx - ax) / len
            var hit = 0
            var total = 0
            for (s in 0 until SAMPLES_PER_SIDE) {
                val t = 0.1 + 0.8 * (s + 0.5) / SAMPLES_PER_SIDE
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                var prev = Double.NaN
                var best = 0.0
                var inside = true
                for (k in -LINE_RANGE..LINE_RANGE) {
                    val x = (px + nx * k).toInt()
                    val y = (py + ny * k).toInt()
                    if (x !in 0..maxX || y !in 0..maxY) { inside = false; break }
                    val v = blurred.get(y, x)[0]
                    if (!prev.isNaN()) best = maxOf(best, kotlin.math.abs(v - prev))
                    prev = v
                }
                if (!inside) continue
                total++
                if (best >= LINE_STEP) hit++
            }
            if (total < SAMPLES_PER_SIDE / 2) return 0.0
            worst = minOf(worst, hit.toDouble() / total)
        }
        return worst
    }

    /**
     * Pre každú stranu porovná jas tesne vnútri a tesne vonku (vzorky po normále strany)
     * a vráti najmenší z mediánov rozdielov. Skutočný okraj papiera má vnútri svetlejšie
     * než vonku po celej dĺžke; vzor látky ani čiara vo vnútri listu túto podmienku nesplnia.
     * Vráti null, ak niektorá strana leží pri okraji záberu (dokument presahuje záber).
     */
    private fun minSideContrast(quad: Quad, w: Double, h: Double, insideBrighter: Boolean): Double? {
        val c = quad.corners.map { Pair(it.x * w, it.y * h) }
        val cx = c.sumOf { it.first } / 4
        val cy = c.sumOf { it.second } / 4
        val maxX = blurred.cols() - 1
        val maxY = blurred.rows() - 1
        var worst = Double.MAX_VALUE
        val diffs = DoubleArray(SAMPLES_PER_SIDE)
        for (i in 0 until 4) {
            val (ax, ay) = c[i]
            val (bx, by) = c[(i + 1) % 4]
            val len = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
            var nx = -(by - ay) / len
            var ny = (bx - ax) / len
            // Normála smerom von (od ťažiska).
            val mx = (ax + bx) / 2
            val my = (ay + by) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny }
            var n = 0
            for (s in 0 until SAMPLES_PER_SIDE) {
                // Vzorky z vnútornej časti strany (bez rohov, kde sa okraje pretínajú).
                val t = 0.1 + 0.8 * (s + 0.5) / SAMPLES_PER_SIDE
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                val ix = (px - nx * CONTRAST_OFFSET).toInt()
                val iy = (py - ny * CONTRAST_OFFSET).toInt()
                val ox = (px + nx * CONTRAST_OFFSET).toInt()
                val oy = (py + ny * CONTRAST_OFFSET).toInt()
                if (ox !in 0..maxX || oy !in 0..maxY || ix !in 0..maxX || iy !in 0..maxY) continue
                val d = blurred.get(iy, ix)[0] - blurred.get(oy, ox)[0]
                diffs[n++] = if (insideBrighter) d else kotlin.math.abs(d)
            }
            // Strana pri okraji záberu (dokument presahuje záber) nemá vonkajšie vzorky.
            if (n < SAMPLES_PER_SIDE / 2) return null
            val sorted = diffs.copyOf(n).also { it.sort() }
            worst = minOf(worst, sorted[n / 2])
        }
        return worst
    }

    fun release() {
        listOf(blurred, edges, binary, reblurred, lap, kernel3, kernel5).forEach { it.release() }
    }

    private companion object {
        const val CANNY_LOW = 20.0
        const val CANNY_HIGH = 60.0
        const val SAMPLES_PER_SIDE = 40
        /** Vzdialenosť vzoriek od strany v pixeloch analyzovaného obrazu. */
        const val CONTRAST_OFFSET = 6.0
        val EPSILONS = doubleArrayOf(0.02, 0.03, 0.04, 0.06)
        /** Strana s väčšou odchýlkou od protiľahlej (°) sa skúsi dorovnať na čiaru rámu. */
        const val REFINE_MIN_SKEW = 4.0
        /** Najväčšia odchýlka čiary rámu od protiľahlej strany (°). */
        const val REFINE_PARALLEL = 3.0
        /** Polovičná šírka pásu naprieč stranou, v ktorom sa hľadá línia hrany (px). */
        const val LINE_RANGE = 6
        /** Najmenší skok jasu medzi susednými pixelmi, ktorý sa počíta ako hrana. */
        const val LINE_STEP = 7.0
        /** Najmenší podiel vzoriek s hranou na každej strane (náhrada kontrastu pri tabuli). */
        const val MIN_LINE_SUPPORT = 0.7
        /** Kontrast priradený plnej podpore línie (pre porovnanie kandidátov). */
        const val LINE_CONTRAST = 30.0
        /** Ďalšie rozmazanie pri meraní ostrosti nezávislej od obsahu. */
        const val REBLUR_SIGMA = 1.5
    }
}
