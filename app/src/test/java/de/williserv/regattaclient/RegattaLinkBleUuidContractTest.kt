package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class RegattaLinkBleUuidContractTest {

    @Test
    fun allocatedRegattaLinkUuids_matchPublicContract() {
        val expected = linkedMapOf(
            "config" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710001",
            "device_name" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710002",
            "device_info" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710003",
            "pgn_inventory" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710004",
            "raw_can" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710005",
            "led_brightness" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710006",
            "diagnostic_log" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710007",
            "device_control" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710008",
            "ota" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710010",
            "ota_control" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710011",
            "ota_data" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710012",
            "ota_status" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710013",
            "telemetry" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710020",
            "fast_motion" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710021",
            "motion_summary" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710022",
            "calibration" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710023",
            "boat_state" to "7f2c4b10-6f63-4a8d-9a3e-2e5d6b710024"
        )

        val actual = linkedMapOf(
            "config" to RegattaLinkBleClient.CONFIG_SERVICE_UUID,
            "device_name" to RegattaLinkBleClient.DEVICE_NAME_UUID,
            "device_info" to RegattaLinkBleClient.DEVICE_INFO_UUID,
            "pgn_inventory" to RegattaLinkBleClient.NMEA_PGN_INVENTORY_UUID,
            "raw_can" to RegattaLinkBleClient.NMEA_RAW_CAN_UUID,
            "led_brightness" to RegattaLinkBleClient.LED_BRIGHTNESS_UUID,
            "diagnostic_log" to RegattaLinkBleClient.DIAGNOSTIC_LOG_UUID,
            "device_control" to RegattaLinkBleClient.DEVICE_CONTROL_UUID,
            "ota" to REGATTALINK_OTA_SERVICE_UUID,
            "ota_control" to REGATTALINK_OTA_CONTROL_UUID,
            "ota_data" to REGATTALINK_OTA_DATA_UUID,
            "ota_status" to REGATTALINK_OTA_STATUS_UUID,
            "telemetry" to RegattaLinkBleClient.TELEMETRY_SERVICE_UUID,
            "fast_motion" to RegattaLinkBleClient.TELEMETRY_FAST_UUID,
            "motion_summary" to RegattaLinkBleClient.TELEMETRY_SUMMARY_UUID,
            "calibration" to RegattaLinkBleClient.TELEMETRY_CALIBRATION_UUID,
            "boat_state" to RegattaLinkBleClient.TELEMETRY_BOAT_STATE_UUID
        )

        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, uuid) ->
            assertEquals("$name UUID", UUID.fromString(uuid), actual.getValue(name))
        }
        assertEquals(actual.size, actual.values.toSet().size)
    }
}
