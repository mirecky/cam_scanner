package sk.mirecky.skener.scanner

import kotlin.math.abs

/** Pozorovanie z jedného snímku analýzy. */
class FrameObservation(
    val timeMs: Long,
    /** Nájdený dokument, alebo null. */
    val quad: Quad?,
    /** Či je obraz dokumentu dostatočne ostrý. */
    val sharp: Boolean,
    /** Miera pohybu v zábere oproti predchádzajúcemu snímku (priemerný rozdiel jasu, 0..255). */
    val motion: Double,
    /** Odtlačok obsahu dokumentu (normalizovaný zmenšený obraz), ak bol nájdený. */
    val fingerprint: FloatArray?,
)

enum class CapturePhase {
    /** Dokument nie je v zábere. */
    SEARCHING,
    /** Dokument je nájdený, čaká sa na ustálenie. */
    STABILIZING,
    /** Prebieha snímanie. */
    CAPTURING,
    /** Strana je nasnímaná, čaká sa na výmenu listu. */
    WAITING_FOR_CHANGE,
}

data class CaptureState(
    val phase: CapturePhase,
    /** Postup ustálenia 0..1 (pre animáciu obrysu). */
    val progress: Float = 0f,
    /** Dokument v zábere je zhodný s naposledy nasnímaným. */
    val sameAsLast: Boolean = false,
)

data class AutoCaptureConfig(
    val stableMs: Long = 600,
    /** Najväčší dovolený posun rohu počas ustaľovania (normalizované jednotky). */
    val maxCornerShift: Double = 0.015,
    /** Pohyb, ktorý ruší ustálenie a zároveň znamená „niečo sa v zábere zmenilo“. */
    val motionThreshold: Double = 4.0,
    /** Posun obrysu oproti nasnímanému, ktorý sa považuje za výmenu listu. */
    val changeCornerShift: Double = 0.05,
    /** Rozdiel odtlačkov, pod ktorým ide o ten istý list. */
    val sameSheetDifference: Double = 0.10,
)

/**
 * Stavový automat automatického snímania (špecifikácia F3).
 *
 * HĽADANIE → USTÁLENIE → SNÍMANIE → ČAKANIE NA ZMENU → HĽADANIE/USTÁLENIE …
 *
 * Po nasnímaní sa ďalšia strana nasníma až vtedy, keď (a) v zábere nastala zmena (pohyb, strata
 * dokumentu alebo výrazný posun obrysu) a (b) obsah ustáleného dokumentu sa líši od posledného
 * nasnímaného. Tým sa zabráni dvojitému nasnímaniu toho istého listu a zároveň sa rozpozná nový
 * list položený presne na miesto predchádzajúceho.
 *
 * Trieda nepoužíva Android ani OpenCV, aby sa dala testovať samostatne.
 */
class AutoCaptureController(private val config: AutoCaptureConfig = AutoCaptureConfig()) {

    private var phase = CapturePhase.SEARCHING
    private var anchor: Quad? = null
    private var stableSince = 0L
    private var lastCapturedQuad: Quad? = null
    private var lastFingerprint: FloatArray? = null
    private var changeSeen = true

    /**
     * Spracuje snímok. Vráti nový stav a príznak, či sa má práve teraz nasnímať strana
     * (iba ak [autoEnabled]).
     */
    fun onFrame(frame: FrameObservation, autoEnabled: Boolean): Pair<CaptureState, Boolean> {
        val quad = frame.quad
        val moving = frame.motion > config.motionThreshold

        if (phase == CapturePhase.CAPTURING) return CaptureState(phase) to false

        if (phase == CapturePhase.WAITING_FOR_CHANGE) {
            val last = lastCapturedQuad
            if (quad == null || moving || (last != null && quad.maxCornerShift(last) > config.changeCornerShift)) {
                changeSeen = true
            }
            if (!changeSeen) return CaptureState(phase, sameAsLast = true) to false
            phase = CapturePhase.SEARCHING
        }

        if (quad == null) {
            phase = CapturePhase.SEARCHING
            anchor = null
            return CaptureState(phase) to false
        }

        val a = anchor
        if (phase == CapturePhase.SEARCHING || a == null || moving || quad.maxCornerShift(a) > config.maxCornerShift) {
            phase = CapturePhase.STABILIZING
            anchor = quad
            stableSince = frame.timeMs
            return CaptureState(phase, progress = 0f) to false
        }

        val elapsed = frame.timeMs - stableSince
        val progress = (elapsed.toFloat() / config.stableMs).coerceIn(0f, 1f)
        if (elapsed < config.stableMs || !frame.sharp) {
            return CaptureState(phase, progress = progress) to false
        }

        // Ustálený a ostrý dokument. Je to nový list?
        val fp = frame.fingerprint
        val previous = lastFingerprint
        if (previous != null && fp != null && difference(fp, previous) < config.sameSheetDifference) {
            phase = CapturePhase.WAITING_FOR_CHANGE
            changeSeen = false
            return CaptureState(phase, sameAsLast = true) to false
        }

        if (!autoEnabled) return CaptureState(phase, progress = 1f) to false

        phase = CapturePhase.CAPTURING
        return CaptureState(phase, progress = 1f) to true
    }

    /** Volá sa po dokončení snímania (automatického aj ručného). */
    fun onCaptured(quad: Quad?, fingerprint: FloatArray?) {
        lastCapturedQuad = quad
        lastFingerprint = fingerprint
        changeSeen = false
        anchor = null
        phase = CapturePhase.WAITING_FOR_CHANGE
    }

    /** Volá sa, ak snímanie zlyhalo. */
    fun onCaptureFailed() {
        anchor = null
        phase = CapturePhase.SEARCHING
    }

    /** Zabudne posledný nasnímaný list (napr. po zmazaní všetkých strán). */
    fun reset() {
        lastCapturedQuad = null
        lastFingerprint = null
        changeSeen = true
        anchor = null
        phase = CapturePhase.SEARCHING
    }

    companion object {
        /** Rozmery odtlačku (musia zodpovedať [ImageOps.fingerprint]). */
        const val FP_W = 64
        const val FP_H = 90
        private const val MAX_SHIFT = 3

        /**
         * Rozdiel dvoch odtlačkov: 1 − najvyššia normalizovaná korelácia pri vzájomnom posune
         * do ±[MAX_SHIFT] pixelov. Ten istý list mierne posunutý dáva hodnotu blízku 0,
         * iný list výrazne vyššiu.
         */
        fun difference(a: FloatArray, b: FloatArray): Double {
            if (a.size != FP_W * FP_H || b.size != a.size) return Double.MAX_VALUE
            var best = -1.0
            for (dy in -MAX_SHIFT..MAX_SHIFT) for (dx in -MAX_SHIFT..MAX_SHIFT) {
                var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var n = 0
                for (y in MAX_SHIFT until FP_H - MAX_SHIFT) {
                    val rowA = y * FP_W
                    val rowB = (y + dy) * FP_W
                    for (x in MAX_SHIFT until FP_W - MAX_SHIFT) {
                        val va = a[rowA + x].toDouble()
                        val vb = b[rowB + x + dx].toDouble()
                        sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb; n++
                    }
                }
                val cov = sab - sa * sb / n
                val den = kotlin.math.sqrt((saa - sa * sa / n) * (sbb - sb * sb / n))
                val r = if (den < 1e-9) 0.0 else cov / den
                if (r > best) best = r
            }
            return 1.0 - best
        }
    }
}
