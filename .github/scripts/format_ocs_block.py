from pathlib import Path

path = Path('app/src/main/java/de/williserv/regattaclient/MainActivity.kt')
text = path.read_text()
function_start = text.index('    private fun updateStartPanelStatus() {')
start = text.index('if (isOcs) {', function_start)
end = text.index('        if (currentRaceStatus.equals("finished"', start)
clean = '''        if (isOcs) {
            val startMillis = raceStartEpochMillis
            val remainingSeconds = if (startMillis != null) {
                (startMillis - System.currentTimeMillis()) / 1000L
            } else {
                null
            }

            if (remainingSeconds != null && remainingSeconds > 0L) {
                val minutes = remainingSeconds / 60L
                val seconds = remainingSeconds % 60L
                startPanelText.value = String.format(Locale.US, "%d:%02d", minutes, seconds)
                startPanelMode.value = "ocs_countdown"
            } else {
                startPanelText.value = getString(R.string.ocs)
                startPanelMode.value = "ocs"
            }
            return
        }

'''
path.write_text(text[:start] + clean + text[end:])
