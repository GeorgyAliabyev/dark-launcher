package org.arkikeskus.launcher.data

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.model.LauncherSettings
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the launcher can signal back after an action. Each one answers the same three ways —
 * the launcher's own soft sound, a short vibration, and its own spoken confirmation — and the
 * person chooses in Settings > Sounds > Signals which of the three answers, so sounds alone are
 * a full mode with the screen reader silent.
 */
enum class FeedbackEvent(@param:StringRes internal val announcement: Int?) {
    /** Touched something: the quietest tick, never spoken. */
    Select(null),

    /** An icon landed on a cell or went into a folder. */
    Place(R.string.feedback_app_placed),

    /** A thing finished: the folder is closed, the choice is saved. */
    Confirm(R.string.feedback_confirmed),

    FolderCreate(R.string.feedback_folder_created),
    FolderOpen(R.string.feedback_folder_opened),
    Move(R.string.feedback_app_moved),

    /** Something left: an app from a folder or a page, a folder from the home screen. */
    Delete(R.string.feedback_deleted),
    FolderDelete(R.string.feedback_folder_deleted),

    /** The action could not be done — the cell was full, the folder refused another app. */
    Error(R.string.feedback_blocked),
}

/**
 * Plays [FeedbackEvent]s. The settings are read once at start and kept in a snapshot, so a tick
 * costs no suspension; the sound pool is warmed at the same time. A click that arrives before
 * the first read falls back to the on-by-default set.
 */
@Singleton
class FeedbackPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var settings = LauncherSettings()

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val sounds: Map<FeedbackEvent, Int> =
        mapOf(
            FeedbackEvent.Select to pool.load(context, R.raw.feedback_select, 1),
            FeedbackEvent.Place to pool.load(context, R.raw.feedback_place, 1),
            FeedbackEvent.Confirm to pool.load(context, R.raw.feedback_confirm, 1),
            FeedbackEvent.FolderCreate to pool.load(context, R.raw.feedback_folder_create, 1),
            FeedbackEvent.FolderOpen to pool.load(context, R.raw.feedback_folder_open, 1),
            FeedbackEvent.Move to pool.load(context, R.raw.feedback_move, 1),
            FeedbackEvent.Delete to pool.load(context, R.raw.feedback_delete, 1),
            FeedbackEvent.FolderDelete to pool.load(context, R.raw.feedback_delete, 1),
            FeedbackEvent.Error to pool.load(context, R.raw.feedback_error, 1),
        )

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var speech: TextToSpeech? = null

    @Volatile
    private var speechReady = false

    /** Starts the settings snapshot and the voice; the sounds were loaded when this was built. */
    fun warmUp(repository: SettingsRepository) {
        scope.launch { repository.settings.collect { settings = it } }
        speech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speech?.language = Locale.getDefault()
                speechReady = true
            }
        }
    }

    /** Answers [event] the way the person set the three switches. */
    fun play(event: FeedbackEvent) {
        val s = settings
        if (s.feedbackSounds) playSound(event)
        if (s.feedbackVibrations) vibrate(event)
        val announcement = event.announcement
        if (s.feedbackSpeech && announcement != null) speak(context.getString(announcement))
    }

    /** Settings' "Try the signals": all three answers at once, whatever the switches say. */
    fun preview() {
        playSound(FeedbackEvent.Confirm)
        vibrate(FeedbackEvent.Confirm)
        speak(context.getString(R.string.feedback_signals_on))
    }

    fun release() {
        speech?.shutdown()
        speech = null
        pool.release()
    }

    private fun playSound(event: FeedbackEvent) {
        val id = sounds[event] ?: return
        if (id == 0) return
        pool.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    private fun vibrate(event: FeedbackEvent) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (event) {
            FeedbackEvent.Select -> VibrationEffect.createOneShot(8, HALF_AMPLITUDE)
            FeedbackEvent.Place, FeedbackEvent.Move -> VibrationEffect.createOneShot(12, HALF_AMPLITUDE)
            FeedbackEvent.Confirm -> VibrationEffect.createWaveform(longArrayOf(0, 14, 45, 18), -1)
            FeedbackEvent.FolderCreate -> VibrationEffect.createWaveform(longArrayOf(0, 16, 50, 22), -1)
            FeedbackEvent.FolderOpen -> VibrationEffect.createWaveform(longArrayOf(0, 10, 30, 12), -1)
            FeedbackEvent.Delete, FeedbackEvent.FolderDelete -> VibrationEffect.createOneShot(28, DEFAULT_AMPLITUDE)
            FeedbackEvent.Error -> VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1)
        }
        v.vibrate(effect)
    }

    private fun speak(text: String) {
        if (!speechReady) return
        speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val MAX_STREAMS = 3
        const val VOLUME = 0.9f
        const val HALF_AMPLITUDE = 90
        const val DEFAULT_AMPLITUDE = 150
        const val UTTERANCE_ID = "dark-launcher-feedback"
    }
}
