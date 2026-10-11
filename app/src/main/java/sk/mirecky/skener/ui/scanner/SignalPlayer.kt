package sk.mirecky.skener.ui.scanner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.VibratorManager
import sk.mirecky.skener.R
import sk.mirecky.skener.data.CaptureSignal

/**
 * Jemný signál po nasnímaní strany: krátky tichý tón, alebo extrémne krátka a slabá vibrácia.
 * Zvuk sa neprehrá, ak je telefón v tichom alebo vibračnom režime.
 */
class SignalPlayer(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val chime = pool.load(context, R.raw.capture_chime, 1)

    fun play(signal: CaptureSignal) {
        when (signal) {
            CaptureSignal.SOUND ->
                if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) pool.play(chime, 0.6f, 0.6f, 1, 0, 1f)
            CaptureSignal.VIBRATION -> vibrateTick()
            CaptureSignal.SILENT -> Unit
        }
    }

    private fun vibrateTick() {
        val effect = if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f).compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        vibrator.vibrate(effect)
    }

    fun release() = pool.release()
}
