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
 * Jemný signál po nasnímaní strany („môžeš vymeniť list“): mäkký dvojtón, alebo extrémne krátka
 * a slabá vibrácia. Tón ide cez hlasitosť médií (ovláda sa bočnými tlačidlami, kým je aplikácia
 * otvorená). Ak je telefón v tichom/vibračnom režime alebo sú médiá stlmené, zavibruje namiesto tónu.
 */
class SignalPlayer(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val chime = pool.load(context, R.raw.capture_chime, 1)

    fun play(signal: CaptureSignal) {
        when (signal) {
            CaptureSignal.SOUND ->
                if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                    pool.play(chime, 1f, 1f, 1, 0, 1f)
                } else {
                    vibrateTick()
                }
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
