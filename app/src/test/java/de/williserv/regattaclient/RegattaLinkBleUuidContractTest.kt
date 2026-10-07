package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class RegattaLinkBleUuidContractTest {

    @Test
    fun allocatedRegattaLinkUuids_matchGattV2Contract() {
        val expected = linkedMapOf(
            "ota" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720001",
            "ota_control" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720002",
            "ota_data" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720003",
            "ota_status" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720004",
            "config" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720010",
            "device_name" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720011",
            "device_info" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720012",
            "pgn_inventory" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720013",
            "raw_can" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720014",
            "led_brightness" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720015",
            "motion_damping" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720016",
            "config_word" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720017",
            "heading_trim" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720018",
            "diagnostic_log" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720019",
            "device_control" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001a",
            "tx_runtime_status" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001b",
            "phone_gnss_input" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001c",
            "telemetry" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720020",
            "fast_motion" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720021",
            "motion_summary" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720022",
            "imu_diagnostics" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720023",
            "boat_state" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720024",
            "motion_one_hz" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720025",
            "load_telemetry" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720026",
            "extension" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b720030"
        )

        val actual = linkedMapOf(
            "ota" to REGATTALINK_OTA_SERVICE_UUID,
            "ota_control" to REGATTALINK_OTA_CONTROL_UUID,
            "ota_data" to REGATTALINK_OTA_DATA_UUID,
            "ota_status" to REGATTALINK_OTA_STATUS_UUID,
            "config" to RegattaLinkBleClient.CONFIG_SERVICE_UUID,
            "device_name" to RegattaLinkBleClient.DEVICE_NAME_UUID,
            "device_info" to RegattaLinkBleClient.DEVICE_INFO_UUID,
            "pgn_inventory" to RegattaLinkBleClient.NMEA_PGN_INVENTORY_UUID,
            "raw_can" to RegattaLinkBleClient.NMEA_RAW_CAN_UUID,
            "led_brightness" to RegattaLinkBleClient.LED_BRIGHTNESS_UUID,
            "motion_damping" to RegattaLinkBleClient.MOTION_DAMPING_UUID,
            "config_word" to RegattaLinkBleClient.CONFIG_WORD_UUID,
            "heading_trim" to RegattaLinkBleClient.HEADING_TRIM_UUID,
            "diagnostic_log" to RegattaLinkBleClient.DIAGNOSTIC_LOG_UUID,
            "device_control" to RegattaLinkBleClient.DEVICE_CONTROL_UUID,
            "tx_runtime_status" to RegattaLinkBleClient.NMEA_TX_RUNTIME_STATUS_UUID,
            "phone_gnss_input" to RegattaLinkBleClient.PHONE_GNSS_INPUT_UUID,
            "telemetry" to RegattaLinkBleClient.TELEMETRY_SERVICE_UUID,
            "fast_motion" to RegattaLinkBleClient.TELEMETRY_FAST_UUID,
            "motion_summary" to RegattaLinkBleClient.TELEMETRY_SUMMARY_UUID,
            "imu_diagnostics" to RegattaLinkBleClient.TELEMETRY_CALIBRATION_UUID,
            "boat_state" to RegattaLinkBleClient.TELEMETRY_BOAT_STATE_UUID,
            "motion_one_hz" to RegattaLinkBleClient.TELEMETRY_MOTION_ONE_HZ_UUID,
            "load_telemetry" to RegattaLinkBleClient.TELEMETRY_LOAD_UUID,
            "extension" to RegattaLinkBleClient.EXTENSION_SERVICE_UUID
        )

        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, uuid) ->
            assertEquals("$name UUID", UUID.fromString(uuid), actual.getValue(name))
        }
        assertEquals(actual.size, actual.values.toSet().size)
    }

    @Test
    fun normalTelemetryUsesOnlyMotionOneHz() {
        assertEquals(
            setOf(RegattaLinkBleClient.TELEMETRY_MOTION_ONE_HZ_UUID),
            RegattaLinkBleClient.NORMAL_TELEMETRY_UUIDS
        )
    }
}
