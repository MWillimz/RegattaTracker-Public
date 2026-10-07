package de.williserv.regattaclient

import android.content.Context
import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal const val REGATTALINK_PHONE_GNSS_FRAME_VERSION = 2
internal const val REGATTALINK_PHONE_GNSS_FRAME_SIZE = 28
internal const val REGATTALINK_PHONE_GNSS_REQUIRED_MTU =
    REGATTALINK_PHONE_GNSS_FRAME_SIZE + 3
internal const val REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS = 1_000L

internal class RegattaLinkPhoneGpsRelayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "regattalink_phone_gps_relay"
        const val KEY_ENABLED = "enabled"
    }
}

internal const val REGATTALINK_PHONE_GNSS_VALID_POSITION = 1 shl 0
internal const val REGATTALINK_PHONE_GNSS_VALID_COG = 1 shl 1
internal const val REGATTALINK_PHONE_GNSS_VALID_SOG = 1 shl 2
internal const val REGATTALINK_PHONE_GNSS_VALID_ACCURACY = 1 shl 3
internal const val REGATTALINK_PHONE_GNSS_VALID_ALTITUDE = 1 shl 4
internal const val REGATTALINK_PHONE_GNSS_VALID_UTC_TIME = 1 shl 5

internal data class RegattaLinkPhoneGnssSample(
    val observationElapsedRealtimeNanos: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val cogDeg: Double? = null,
    val sogMps: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val altitudeM: Double? = null,
    val utcTimeMs: Long? = null
)

internal fun regattaLinkRetainedPhoneGnssCog(
    currentCogDeg: Double?,
    previousCogDeg: Double?
): Double? =
    currentCogDeg?.takeIf(Double::isFinite)
        ?: previousCogDeg?.takeIf(Double::isFinite)

internal fun regattaLinkPhoneGnssSample(location: Location): RegattaLinkPhoneGnssSample =
    RegattaLinkPhoneGnssSample(
        observationElapsedRealtimeNanos = location.elapsedRealtimeNanos,
        utcTimeMs = location.time.takeIf { it > 0L },
        latitudeDeg = location.latitude,
        longitudeDeg = location.longitude,
        cogDeg = location.bearing
            .toDouble()
            .takeIf { location.hasBearing() },
        sogMps = location.speed
            .toDouble()
            .takeIf { location.hasSpeed() },
        horizontalAccuracyM = location.accuracy
            .toDouble()
            .takeIf { location.hasAccuracy() },
        altitudeM = location.altitude
            .takeIf { location.hasAltitude() }
    )

internal fun encodeRegattaLinkPhoneGnss(
    sample: RegattaLinkPhoneGnssSample,
    sendElapsedRealtimeNanos: Long
): ByteArray? {
    if (
        !sample.latitudeDeg.isFinite() ||
        !sample.longitudeDeg.isFinite() ||
        sample.latitudeDeg !in -90.0..90.0 ||
        sample.longitudeDeg !in -180.0..180.0
    ) {
        return null
    }

    var validity = REGATTALINK_PHONE_GNSS_VALID_POSITION

    val latitudeScaled = (sample.latitudeDeg * 10_000_000.0).roundToInt()
    val longitudeScaled = (sample.longitudeDeg * 10_000_000.0).roundToInt()

    val cogCentideg = sample.cogDeg
        ?.takeIf(Double::isFinite)
        ?.let { raw ->
            val normalized = ((raw % 360.0) + 360.0) % 360.0
            ((normalized * 100.0).roundToInt() % 36_000)
        }
        ?.also { validity = validity or REGATTALINK_PHONE_GNSS_VALID_COG }
        ?: 0

    val sogCms = sample.sogMps
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { it * 100.0 }
        ?.takeIf { it <= 65_535.0 }
        ?.roundToInt()
        ?.also { validity = validity or REGATTALINK_PHONE_GNSS_VALID_SOG }
        ?: 0

    val accuracyCm = sample.horizontalAccuracyM
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { (it * 100.0).coerceAtMost(65_535.0).roundToInt() }
        ?.also { validity = validity or REGATTALINK_PHONE_GNSS_VALID_ACCURACY }
        ?: 0

    val altitudeDm = sample.altitudeM
        ?.takeIf(Double::isFinite)
        ?.let { it * 10.0 }
        ?.takeIf {
            it >= Short.MIN_VALUE.toDouble() &&
                it <= Short.MAX_VALUE.toDouble()
        }
        ?.roundToInt()
        ?.also { validity = validity or REGATTALINK_PHONE_GNSS_VALID_ALTITUDE }
        ?: 0

    val utcTimeMs = sample.utcTimeMs
        ?.takeIf { it > 0L }
        ?.also { validity = validity or REGATTALINK_PHONE_GNSS_VALID_UTC_TIME }
        ?: 0L

    val ageNanos =
        (sendElapsedRealtimeNanos - sample.observationElapsedRealtimeNanos)
            .coerceAtLeast(0L)
    val sampleAgeMs =
        (ageNanos / 1_000_000L)
            .coerceAtMost(65_535L)
            .toInt()

    return ByteBuffer.allocate(REGATTALINK_PHONE_GNSS_FRAME_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(REGATTALINK_PHONE_GNSS_FRAME_VERSION.toByte())
        .put(validity.toByte())
        .putShort(sampleAgeMs.toShort())
        .putInt(latitudeScaled)
        .putInt(longitudeScaled)
        .putShort(cogCentideg.toShort())
        .putShort(sogCms.toShort())
        .putShort(accuracyCm.toShort())
        .putShort(altitudeDm.toShort())
        .putLong(utcTimeMs)
        .array()
}

internal fun regattaLinkPhoneGnssForwardingGate(
    connected: Boolean,
    transportReady: Boolean,
    otaActive: Boolean,
    configurationState: RegattaLinkConfigurationState
): Boolean {
    val configWord = configurationState.configWord ?: return false
    return connected &&
        transportReady &&
        !otaActive &&
        !configurationState.deviceControlBusy &&
        !configurationState.restartAwaitingDisconnect &&
        !configurationState.factoryResetAwaitingDisconnect &&
        configurationState.factoryResetWriteAcceptedRequestId == null &&
        configWord and REGATTALINK_CONFIG_SESSION_CAN != 0u &&
        configWord and REGATTALINK_CONFIG_TX_MASTER != 0u &&
        configWord and REGATTALINK_CONFIG_TX_PHONE_GPS != 0u
}

internal fun regattaLinkLocationRequestIntervalMs(
    persistenceIntervalMs: Long,
    phoneGnssForwarding: Boolean
): Long =
    if (phoneGnssForwarding) {
        minOf(persistenceIntervalMs, REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS)
    } else {
        persistenceIntervalMs
    }

internal fun regattaLinkPhoneGnssTransportReady(
    negotiatedMtu: Int
): Boolean = negotiatedMtu >= REGATTALINK_PHONE_GNSS_REQUIRED_MTU
