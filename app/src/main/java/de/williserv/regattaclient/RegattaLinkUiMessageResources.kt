package de.williserv.regattaclient

import androidx.annotation.StringRes

@StringRes
internal fun regattaLinkUiMessageResource(
    message: RegattaLinkUiMessage
): Int = when (message) {
    RegattaLinkUiMessage.BLUETOOTH_PERMISSION_DENIED ->
        R.string.regattalink_permission_denied
    RegattaLinkUiMessage.BLUETOOTH_DISABLED ->
        R.string.regattalink_error_bluetooth_disabled
    RegattaLinkUiMessage.BLUETOOTH_UNAVAILABLE ->
        R.string.regattalink_error_bluetooth_unavailable
    RegattaLinkUiMessage.CONNECTION_FAILED ->
        R.string.regattalink_status_error
    RegattaLinkUiMessage.CONNECTION_TIMEOUT ->
        R.string.regattalink_error_connection_timeout
    RegattaLinkUiMessage.PAIRING_START_FAILED ->
        R.string.regattalink_error_pairing_start_failed
    RegattaLinkUiMessage.CONNECTION_OPEN_FAILED ->
        R.string.regattalink_error_connection_open_failed
    RegattaLinkUiMessage.TELEMETRY_FAILED ->
        R.string.regattalink_error_telemetry_failed
    RegattaLinkUiMessage.TELEMETRY_UNAVAILABLE ->
        R.string.regattalink_error_telemetry_unavailable
    RegattaLinkUiMessage.TELEMETRY_INVALID ->
        R.string.regattalink_error_telemetry_invalid
    RegattaLinkUiMessage.CONFIGURATION_FAILED ->
        R.string.regattalink_error_configuration_failed
    RegattaLinkUiMessage.DEVICE_CONTROL_UNSUPPORTED ->
        R.string.regattalink_error_device_control_unsupported
    RegattaLinkUiMessage.DEVICE_CONTROL_BUSY ->
        R.string.regattalink_error_device_control_busy
    RegattaLinkUiMessage.DEVICE_CONTROL_NOT_READY ->
        R.string.regattalink_error_device_control_not_ready
    RegattaLinkUiMessage.DEVICE_CONTROL_REJECTED ->
        R.string.regattalink_error_device_control_rejected
    RegattaLinkUiMessage.CALIBRATION_MOTION_REJECTED ->
        R.string.regattalink_error_calibration_motion_rejected
    RegattaLinkUiMessage.CALIBRATION_ORIENTATION_REJECTED ->
        R.string.regattalink_error_calibration_orientation_rejected
    RegattaLinkUiMessage.CALIBRATION_PERSIST_FAILED ->
        R.string.regattalink_error_calibration_persist_failed
    RegattaLinkUiMessage.FACTORY_RESET_CONFIG_FAILED ->
        R.string.regattalink_error_factory_reset_config_failed
    RegattaLinkUiMessage.FACTORY_RESET_BOND_FAILED ->
        R.string.regattalink_error_factory_reset_bond_failed
    RegattaLinkUiMessage.DEVICE_CONTROL_INTERNAL_FAILED ->
        R.string.regattalink_error_device_control_internal_failed
    RegattaLinkUiMessage.CALIBRATION_TIMEOUT ->
        R.string.regattalink_error_calibration_timeout
    RegattaLinkUiMessage.NAME_CHANGE_FAILED ->
        R.string.regattalink_error_name_change_failed
    RegattaLinkUiMessage.LED_BRIGHTNESS_RANGE ->
        R.string.regattalink_error_led_brightness_range
    RegattaLinkUiMessage.MOTION_DAMPING_RANGE ->
        R.string.regattalink_error_motion_damping_range
    RegattaLinkUiMessage.NMEA_FAILED ->
        R.string.regattalink_error_nmea_failed
    RegattaLinkUiMessage.NMEA_NOTIFICATIONS_FAILED ->
        R.string.regattalink_error_nmea_notifications_failed
    RegattaLinkUiMessage.NMEA_BOAT_STATE_READ_FAILED ->
        R.string.regattalink_error_nmea_boat_state_read_failed
    RegattaLinkUiMessage.NMEA_PGN_INVENTORY_READ_FAILED ->
        R.string.regattalink_error_nmea_pgn_inventory_read_failed
    RegattaLinkUiMessage.NMEA_RAW_CAN_READ_FAILED ->
        R.string.regattalink_error_nmea_raw_can_read_failed
    RegattaLinkUiMessage.RAW_CAPTURE_FAILED ->
        R.string.regattalink_error_raw_capture_failed
    RegattaLinkUiMessage.RAW_CAPTURE_DIRECTORY_FAILED ->
        R.string.regattalink_error_raw_capture_directory_failed
    RegattaLinkUiMessage.RAW_CAPTURE_FILE_FAILED ->
        R.string.regattalink_error_raw_capture_file_failed
    RegattaLinkUiMessage.RAW_CAPTURE_START_FAILED ->
        R.string.regattalink_error_raw_capture_start_failed
    RegattaLinkUiMessage.RAW_CAPTURE_EXPORT_FAILED ->
        R.string.regattalink_error_raw_capture_export_failed
    RegattaLinkUiMessage.OTA_WAIT_FACTORY_RESET ->
        R.string.regattalink_error_ota_wait_factory_reset
    RegattaLinkUiMessage.OTA_WAIT_CONFIGURATION ->
        R.string.regattalink_error_ota_wait_configuration
    RegattaLinkUiMessage.OTA_CONNECT_FIRST ->
        R.string.regattalink_error_ota_connect_first
    RegattaLinkUiMessage.OTA_FAILED ->
        R.string.regattalink_ota_error
    RegattaLinkUiMessage.FIRMWARE_CONNECT_FIRST ->
        R.string.regattalink_connect_before_firmware
    RegattaLinkUiMessage.FIRMWARE_SERVER_REQUIRED ->
        R.string.regattalink_firmware_server_required
    RegattaLinkUiMessage.FIRMWARE_CHECK_FAILED ->
        R.string.regattalink_firmware_check_failed
}
