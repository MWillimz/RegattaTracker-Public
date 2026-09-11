package de.williserv.regattaclient

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal class RaceSignalController(context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val appContext = context.applicationContext
    private val racePrefs = appContext.getSharedPreferences(
        RaceSignalPreferences.RACE_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val localStatusPrefs = appContext.getSharedPreferences(
        RaceSignalPreferences.LOCAL_STATUS_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val handler = Handler(Looper.getMainLooper())
    private val player = RaceSignalPlayer(handler)

    private var activeEventKey: String? = null
    private var schedule: RaceSignalSchedule? = null
    private var started = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateAndPlay()
            scheduleNextTick()
        }
    }

    fun start() {
        if (started) return
        started = true

        racePrefs.registerOnSharedPreferenceChangeListener(this)
        localStatusPrefs.registerOnSharedPreferenceChangeListener(this)
        refreshConfiguration()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        when (sharedPreferences) {
            racePrefs -> refreshConfiguration()
            localStatusPrefs -> {
                if (key == KEY_RACE_FINISHED || key == null) {
                    evaluateAndPlay()
                }
            }
        }
    }

    private fun refreshConfiguration() {
        val enabled = RaceSignalPreferences.isEnabledForCurrentRace(racePrefs)
        val eventKey = if (enabled) RaceSignalPreferences.currentEventKey(racePrefs) else null

        if (eventKey != activeEventKey || (eventKey != null && schedule == null)) {
            activeEventKey = eventKey
            handler.removeCallbacks(tickRunnable)

            schedule = if (eventKey != null) {
                RaceSignalSchedule().also {
                    it.reset(
                        nowMillis = System.currentTimeMillis(),
                        startEpochMillis = currentStartEpochMillis(),
                        allowStartSignals = startSignalsAllowed(),
                        raceFinished = localStatusPrefs.getBoolean(KEY_RACE_FINISHED, false)
                    )
                }
            } else {
                null
            }
        } else if (eventKey != null) {
            evaluateAndPlay()
        }

        scheduleNextTick()
    }

    private fun evaluateAndPlay() {
        if (!RaceSignalPreferences.isEnabledForCurrentRace(racePrefs)) {
            refreshConfiguration()
            return
        }

        val activeSchedule = schedule ?: return
        val cues = activeSchedule.update(
            nowMillis = System.currentTimeMillis(),
            startEpochMillis = currentStartEpochMillis(),
            allowStartSignals = startSignalsAllowed(),
            raceFinished = localStatusPrefs.getBoolean(KEY_RACE_FINISHED, false)
        )

        cues.forEach(player::play)
    }

    private fun scheduleNextTick() {
        handler.removeCallbacks(tickRunnable)
        if (schedule == null || !RaceSignalPreferences.isEnabledForCurrentRace(racePrefs)) return

        val startMillis = currentStartEpochMillis() ?: return
        val remainingMillis = startMillis - System.currentTimeMillis()

        val delayMillis = when {
            remainingMillis > SIX_MINUTES_MILLIS -> {
                (remainingMillis - SIX_MINUTES_MILLIS)
                    .coerceAtMost(10_000L)
                    .coerceAtLeast(1_000L)
            }

            remainingMillis >= -2_000L -> 200L
            else -> return
        }

        handler.postDelayed(tickRunnable, delayMillis)
    }

    private fun startSignalsAllowed(): Boolean {
        return when (racePrefs.getString(KEY_RACE_STATUS_RAW, "").orEmpty().trim().lowercase()) {
            "postponed", "cancelled", "canceled" -> false
            else -> true
        }
    }

    private fun currentStartEpochMillis(): Long? {
        return parseServerTime(
            racePrefs.getString(KEY_RACE_START_RAW, "").orEmpty()
        )?.toEpochMilli()
    }

    private fun parseServerTime(value: String): Instant? {
        if (value.isBlank()) return null

        return try {
            when {
                value.endsWith("Z") -> Instant.parse(value)
                value.contains("+") || value.drop(10).contains("-") -> {
                    OffsetDateTime.parse(value).toInstant()
                }

                value.count { it == ':' } == 1 -> {
                    LocalDateTime.parse("${value}:00")
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                }

                value.count { it == ':' } == 2 -> {
                    LocalDateTime.parse(value)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_RACE_START_RAW = "race_start_raw"
        const val KEY_RACE_STATUS_RAW = "race_status_raw"
        const val KEY_RACE_FINISHED = "race_finished"
        const val SIX_MINUTES_MILLIS = 6 * 60 * 1000L
    }
}

private class RaceSignalPlayer(
    private val handler: Handler
) {
    fun play(cue: RaceSignalCue) {
        when (cue) {
            RaceSignalCue.FIVE_MINUTES,
            RaceSignalCue.FOUR_MINUTES,
            RaceSignalCue.ONE_MINUTE -> playTone(durationMillis = 400)

            RaceSignalCue.START -> playTone(durationMillis = 1_200)
            RaceSignalCue.FINISH -> {
                playTone(durationMillis = 300)
                playTone(durationMillis = 300, delayMillis = 500)
            }
        }
    }

    private fun playTone(durationMillis: Int, delayMillis: Long = 0L) {
        if (delayMillis == 0L) {
            startTone(durationMillis)
        } else {
            handler.postDelayed({ startTone(durationMillis) }, delayMillis)
        }
    }

    private fun startTone(durationMillis: Int) {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: RuntimeException) {
            null
        } ?: return

        val started = try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMillis)
        } catch (_: RuntimeException) {
            false
        }

        if (!started) {
            release(generator)
            return
        }

        handler.postDelayed(
            { release(generator) },
            durationMillis + 100L
        )
    }

    private fun release(generator: ToneGenerator) {
        try {
            generator.release()
        } catch (_: RuntimeException) {
        }
    }
}
