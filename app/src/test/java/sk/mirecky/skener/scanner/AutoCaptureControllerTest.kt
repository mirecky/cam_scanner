package sk.mirecky.skener.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureControllerTest {

    private val quad = Quad(Pt(0.2, 0.15), Pt(0.8, 0.15), Pt(0.8, 0.85), Pt(0.2, 0.85))
    private val n = AutoCaptureController.FP_W * AutoCaptureController.FP_H
    private val sheetA = FloatArray(n) { if ((it / 7) % 2 == 0) 1f else -1f }
    private val sheetB = FloatArray(n) { if ((it * 31 / 11) % 5 == 0) 1.4f else -0.7f }

    private var time = 0L
    private val controller = AutoCaptureController()

    private fun frame(q: Quad? = quad, fp: FloatArray? = sheetA, motion: Double = 0.5, sharp: Boolean = true) =
        FrameObservation(time, q, sharp, motion, if (q == null) null else fp)

    /** Posúva čas po 50 ms a vráti, či niektorý snímok vyvolal nasnímanie. */
    private fun run(ms: Long, auto: Boolean = true, make: () -> FrameObservation): Boolean {
        var captured = false
        val end = time + ms
        while (time < end) {
            time += 50
            val (_, capture) = controller.onFrame(make(), auto)
            if (capture) {
                captured = true
                controller.onCaptured(make().quad, make().fingerprint)
            }
        }
        return captured
    }

    @Test fun capturesAfterDocumentIsStable() {
        assertFalse(run(400) { frame() })
        assertTrue(run(400) { frame() })
    }

    @Test fun doesNotCaptureWithoutDocument() {
        assertFalse(run(3000) { frame(q = null) })
    }

    @Test fun doesNotCaptureBlurredDocument() {
        assertFalse(run(3000) { frame(sharp = false) })
    }

    @Test fun doesNotCaptureWhenAutoDisabled() {
        assertFalse(run(3000, auto = false) { frame() })
    }

    @Test fun movingDocumentDelaysCapture() {
        var i = 0
        assertFalse(run(2000) {
            i++
            val dx = (i % 2) * 0.03
            frame(q = quad.copy(tl = Pt(0.2 + dx, 0.15)))
        })
    }

    @Test fun sameSheetIsNotCapturedTwice() {
        assertTrue(run(1000) { frame() })
        // Ten istý list leží ďalej, nič sa nedeje.
        assertFalse(run(5000) { frame() })
        // Ruka prejde nad listom, ale list ostane ten istý.
        assertFalse(run(300) { frame(motion = 20.0) })
        assertFalse(run(3000) { frame() })
    }

    @Test fun newSheetOnSamePlaceIsCaptured() {
        assertTrue(run(1000) { frame(fp = sheetA) })
        // Výmena listu: pohyb a krátka strata dokumentu.
        assertFalse(run(300) { frame(motion = 25.0) })
        assertFalse(run(200) { frame(q = null) })
        // Nový list na tom istom mieste.
        assertTrue(run(1500) { frame(fp = sheetB) })
    }

    @Test fun capturesSeriesOfSheets() {
        var count = 0
        val sheets = (0 until 5).map { s -> FloatArray(n) { (((it * (s + 3) * 7919) / 13) % 17 - 8).toFloat() } }
        for (s in sheets) {
            if (run(1500) { frame(fp = s) }) count++
            run(300) { frame(motion = 30.0) }
            run(200) { frame(q = null) }
        }
        assertEquals(5, count)
    }

    @Test fun differenceOfIdenticalFingerprintsIsZero() {
        assertEquals(0.0, AutoCaptureController.difference(sheetA, sheetA), 1e-9)
    }
}
