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
    /** Rozdiel obsahu oproti naposledy nasnímanému listu, ak bol vyhodnotený (diagnostika). */
    val difference: Double? = null,
)

data class AutoCaptureConfig(
    val stableMs: Long = 600,
    /** Najväčší dovolený posun rohu počas ustaľovania (normalizované jednotky). */
    val maxCornerShift: Double = 0.015,
    /** Pohyb, ktorý ruší ustálenie a zároveň znamená „niečo sa v zábere zmenilo“. */
    val motionThreshold: Double = 4.0,
    /** Ako dlho musí dokument v zábere chýbať, aby sa to považovalo za výmenu listu. */
    val lostMsForChange: Long = 300,
    /** Najkratší čas medzi dvoma automatickými snímkami. */
    val minIntervalMs: Long = 1_500,
    /**
     * Rozdiel odtlačkov, pod ktorým ide o ten istý list, ak sa v zábere len pohlo
     * (ruka, otras telefónu) – list zostal ležať na mieste.
     */
    val sameSheetDifference: Double = 0.06,
    /**
     * Prísnejšia hranica po tom, čo dokument zo záberu na chvíľu zmizol (list bol zdvihnutý):
     * vtedy ide takmer isto o nový list, aj keď má rovnaké rozloženie (formuláre, výsledky vyšetrení).
     */
    val sameSheetDifferenceAfterLost: Double = 0.025,
    /** Celkový posun od začiatku ustaľovania, ktorý ešte nie je „posúvanie záberu“ (násobok [maxCornerShift]). */
    val maxDriftFactor: Double = 3.0,
)

/**
 * Stavový automat automatického snímania (špecifikácia F3).
 *
 * HĽADANIE → USTÁLENIE → SNÍMANIE → ČAKANIE NA ZMENU → HĽADANIE/USTÁLENIE …
 *
 * Po nasnímaní sa ďalšia strana nasníma až vtedy, keď (a) v zábere nastala zmena (pohyb alebo
 * dokument dlhšie chýba v zábere) a (b) obsah ustáleného dokumentu sa líši od posledného
 * nasnímaného. Tým sa zabráni dvojitému nasnímaniu toho istého listu a zároveň sa rozpozná nový
 * list položený presne na miesto predchádzajúceho.
 *
 * Trieda nepoužíva Android ani OpenCV, aby sa dala testovať samostatne.
 */
class AutoCaptureController(private val config: AutoCaptureConfig = AutoCaptureConfig()) {

    private var phase = CapturePhase.SEARCHING
    private var anchor: Quad? = null
    private var previous: Quad? = null
    private var stableSince = 0L
    private var lastFingerprint: FloatArray? = null
    private var changeSeen = true
    /** Zmena v zábere bola zmiznutie dokumentu (zdvihnutý list), nie iba pohyb. */
    private var sheetLifted = false
    private var lostSince = -1L
    private var lastCaptureAt = Long.MIN_VALUE / 2

    /**
     * Spracuje snímok. Vráti nový stav a príznak, či sa má práve teraz nasnímať strana
     * (iba ak [autoEnabled]).
     */
    fun onFrame(frame: FrameObservation, autoEnabled: Boolean): Pair<CaptureState, Boolean> {
        val quad = frame.quad
        val moving = frame.motion > config.motionThreshold

        if (phase == CapturePhase.CAPTURING) return CaptureState(phase) to false

        // Ako dlho už dokument v zábere chýba (krátke výpadky detekcie sa neberú ako výmena listu).
        if (quad == null) {
            if (lostSince < 0) lostSince = frame.timeMs
        } else {
            lostSince = -1
        }
        val lostLongEnough = lostSince >= 0 && frame.timeMs - lostSince >= config.lostMsForChange

        if (lostLongEnough) sheetLifted = true

        if (phase == CapturePhase.WAITING_FOR_CHANGE) {
            if (moving || lostLongEnough) changeSeen = true
            if (!changeSeen) return CaptureState(phase, sameAsLast = true) to false
            phase = CapturePhase.SEARCHING
        }

        if (quad == null) {
            phase = CapturePhase.SEARCHING
            anchor = null
            previous = null
            return CaptureState(phase) to false
        }

        // Ustálenie: bez náhlych skokov medzi snímkami a bez väčšieho posunu od začiatku
        // (pomalé chvenie ruky ustálenie neruší, presúvanie záberu áno).
        val a = anchor
        val p = previous
        val jumped = p != null && quad.maxCornerShift(p) > config.maxCornerShift
        val drifted = a != null && quad.maxCornerShift(a) > config.maxCornerShift * config.maxDriftFactor
        previous = quad
        if (phase == CapturePhase.SEARCHING || a == null || moving || jumped || drifted) {
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
        val last = lastFingerprint
        val difference = if (last != null && fp != null) difference(fp, last) else null
        val sameLimit = if (sheetLifted) config.sameSheetDifferenceAfterLost else config.sameSheetDifference
        if (difference != null && difference < sameLimit) {
            phase = CapturePhase.WAITING_FOR_CHANGE
            changeSeen = false
            return CaptureState(phase, sameAsLast = true, difference = difference) to false
        }

        if (!autoEnabled || frame.timeMs - lastCaptureAt < config.minIntervalMs) {
            return CaptureState(phase, progress = 1f, difference = difference) to false
        }

        phase = CapturePhase.CAPTURING
        lastCaptureAt = frame.timeMs
        sheetLifted = false
        return CaptureState(phase, progress = 1f, difference = difference) to true
    }

    /** Volá sa po dokončení snímania (automatického aj ručného). */
    fun onCaptured(fingerprint: FloatArray?) {
        lastFingerprint = fingerprint
        changeSeen = false
        sheetLifted = false
        anchor = null
        previous = null
        phase = CapturePhase.WAITING_FOR_CHANGE
    }

    /** Volá sa, ak snímanie zlyhalo. */
    fun onCaptureFailed() {
        anchor = null
        phase = CapturePhase.SEARCHING
    }

    /** Zabudne posledný nasnímaný list (napr. po zmazaní všetkých strán). */
    fun reset() {
        lastFingerprint = null
        changeSeen = true
        sheetLifted = false
        anchor = null
        previous = null
        phase = CapturePhase.SEARCHING
    }

    companion object {
        /** Rozmery odtlačku (musia zodpovedať [ImageOps.fingerprint]). */
        const val FP_W = 96
        const val FP_H = 136
        private const val MAX_SHIFT = 4

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
