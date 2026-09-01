from pathlib import Path
import re
import textwrap

main = Path('app/src/main/java/de/williserv/regattaclient/MainActivity.kt')
home = Path('app/src/main/java/de/williserv/regattaclient/HomeScreen.kt')
test = Path('app/src/test/java/de/williserv/regattaclient/HomeUploadStatusTest.kt')


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one match, found {count}: {old[:180]!r}')
    path.write_text(text.replace(old, new, 1))


replace_once(
    main,
    '    private val uploadStatusText = mutableStateOf("")\n    private val serviceStatusText = mutableStateOf("")\n',
    '    private val uploadStatusText = mutableStateOf("")\n'
    '    private val pendingUploadCount = mutableStateOf(0L)\n'
    '    private val serviceStatusText = mutableStateOf("")\n'
)
replace_once(
    main,
    '                            uploadStatusText = uploadStatusText.value,\n                            debugErrorText = debugErrorText.value,\n',
    '                            uploadStatusText = uploadStatusText.value,\n'
    '                            pendingUploadCount = pendingUploadCount.value,\n'
    '                            debugErrorText = debugErrorText.value,\n'
)
replace_once(
    main,
    '                            raceInfoText = raceInfoText.value,\n                            raceShortenedText = raceShortenedText.value,\n                            raceStartFlags = raceStartFlags.value,\n',
    '                            raceInfoText = raceInfoText.value,\n'
    '                            raceShortenedText = raceShortenedText.value,\n'
    '                            raceShortened = rawRaceCourseShortened,\n'
    '                            hasRaceInfo = rawRaceInfo.trim().let { it.isNotBlank() && it != "--" },\n'
    '                            raceStartFlags = raceStartFlags.value,\n'
)
replace_once(
    main,
    '        rowCountText.value = getString(R.string.rows_stored, total)\n\n        uploadStatusText.value = if (pending == 0L) {\n',
    '        rowCountText.value = getString(R.string.rows_stored, total)\n'
    '        pendingUploadCount.value = pending\n\n'
    '        uploadStatusText.value = if (pending == 0L) {\n'
)

main_text = main.read_text()
ocs_pattern = re.compile(
    r'        if \(isOcs\) \{\n'
    r'            val startMillis = raceStartEpochMillis\n'
    r'            val remainingSeconds = if \(startMillis != null\) \{\n'
    r'                \(startMillis - System\.currentTimeMillis\(\)\) / 1000L\n'
    r'            \} else \{\n'
    r'                null\n'
    r'            \}\n\n'
    r'            startPanelText\.value = if \(remainingSeconds != null && remainingSeconds > 0L\) \{\n'
    r'                val minutes = remainingSeconds / 60L\n'
    r'                val seconds = remainingSeconds % 60L\n\n'
    r'                getString\(R\.string\.start_in, minutes, seconds\)\n'
    r'            \} else \{\n'
    r'                getString\(R\.string\.ocs\)\n'
    r'            \}\n\n'
    r'            startPanelMode\.value = "ocs"\n'
    r'            return\n'
    r'        \}\n'
)
if len(ocs_pattern.findall(main_text)) != 1:
    raise SystemExit('MainActivity: expected one OCS display-state block')
main_text = ocs_pattern.sub(textwrap.dedent('''\
        if (isOcs) {
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
''').replace('\n', '\n        ', 1), main_text, count=1)
main.write_text(main_text)

replace_once(
    home,
    '    uploadStatusText: String,\n    debugErrorText: String,\n',
    '    uploadStatusText: String,\n    pendingUploadCount: Long,\n    debugErrorText: String,\n'
)
replace_once(
    home,
    '    raceInfoText: String,\n    raceShortenedText: String,\n    raceStartFlags: RaceStartFlags,\n',
    '    raceInfoText: String,\n'
    '    raceShortenedText: String,\n'
    '    raceShortened: Boolean,\n'
    '    hasRaceInfo: Boolean,\n'
    '    raceStartFlags: RaceStartFlags,\n'
)
replace_once(
    home,
    '    val advancedUploadStatusText = mergeTelemetryUploadStatusText(\n        pendingStatusText = uploadStatusText,\n        workerStatus = localizedWorkerStatus,\n',
    '    val advancedUploadStatusText = mergeTelemetryUploadStatusText(\n'
    '        pendingStatusText = uploadStatusText,\n'
    '        pendingCount = pendingUploadCount,\n'
    '        workerStatus = localizedWorkerStatus,\n'
)
replace_once(
    home,
    '    val uploadColor = uploadStatusColor(\n        uploadStatusText = uploadStatusText,\n        inRace = inRace,\n',
    '    val uploadColor = uploadStatusColor(\n'
    '        pendingUploadCount = pendingUploadCount,\n'
    '        inRace = inRace,\n'
)
replace_once(
    home,
    '    val showCourseShortened =\n        raceShortenedText == stringResource(R.string.course_shortened_yes) &&\n                !raceStatusCode.equals("finished", ignoreCase = true) &&\n                !raceStatusCode.equals("cancelled", ignoreCase = true)\n',
    '    val showCourseShortened =\n'
    '        raceShortened &&\n'
    '                !raceStatusCode.equals("finished", ignoreCase = true) &&\n'
    '                !raceStatusCode.equals("cancelled", ignoreCase = true)\n'
)
text = home.read_text()
info_pattern = re.compile(
    r'    val hasRaceInfo = raceInfoText\n'
    r'        \.removePrefix\(infoPrefix\)\n'
    r'        \.trim\(\)\n'
    r'        \.let \{ it\.isNotBlank\(\) && it != "--" \}\n'
)
if len(info_pattern.findall(text)) != 1:
    raise SystemExit('HomeScreen: expected one localized hasRaceInfo parser')
home.write_text(info_pattern.sub('', text, count=1))
replace_once(
    home,
    '            uploadStatusText = shortUploadStatus(\n                uploadStatusText = uploadStatusText,\n                inRace = inRace,\n',
    '            uploadStatusText = shortUploadStatus(\n'
    '                pendingUploadCount = pendingUploadCount,\n'
    '                inRace = inRace,\n'
)
replace_once(
    home,
    '                if (startPanelMode == "ocs") {\n                    onOcsPanelClick()\n                }\n',
    '                if (startPanelMode == "ocs" || startPanelMode == "ocs_countdown") {\n'
    '                    onOcsPanelClick()\n'
    '                }\n'
)

text = home.read_text()
for old, new in (
    ('        "ocs" -> MaterialTheme.colorScheme.error\n', '        "ocs", "ocs_countdown" -> MaterialTheme.colorScheme.error\n'),
    ('        "ocs" -> MaterialTheme.colorScheme.onError\n', '        "ocs", "ocs_countdown" -> MaterialTheme.colorScheme.onError\n'),
):
    if text.count(old) != 1:
        raise SystemExit(f'HomeScreen: expected one HeaderPanel mode branch: {old!r}')
    text = text.replace(old, new, 1)
old_text_branch = '''        "ocs" -> {
            if (startPanelText != stringResource(R.string.ocs)) {
                stringResource(R.string.ocs_countdown, startPanelText.substringAfterLast(" ", startPanelText))
            } else {
                stringResource(R.string.ocs)
            }
        }
'''
new_text_branch = '''        "ocs" -> stringResource(R.string.ocs)
        "ocs_countdown" -> stringResource(R.string.ocs_countdown, startPanelText)
'''
if text.count(old_text_branch) != 1:
    raise SystemExit('HomeScreen: expected one OCS text parsing branch')
text = text.replace(old_text_branch, new_text_branch, 1)
old_click = '.clickable(enabled = startPanelMode == "ocs") {'
if text.count(old_click) != 1:
    raise SystemExit('HomeScreen: expected one OCS clickable predicate')
text = text.replace(old_click, '.clickable(enabled = startPanelMode == "ocs" || startPanelMode == "ocs_countdown") {', 1)
home.write_text(text)

text = home.read_text()
merge_pattern = re.compile(r'internal fun mergeTelemetryUploadStatusText\(.*?\n\}\n\n@Composable\nfun TopEventName\(', re.S)
if len(merge_pattern.findall(text)) != 1:
    raise SystemExit('HomeScreen: expected one mergeTelemetry helper block')
new_merge = textwrap.dedent('''\
internal fun mergeTelemetryUploadStatusText(
    pendingStatusText: String,
    pendingCount: Long,
    workerStatus: String,
    uploadWorkerPending: (String, Long) -> String = { worker, pending -> "Upload: $worker · $pending pending" },
    uploadWorker: (String) -> String = { worker -> "Upload: $worker" }
): String {
    if (workerStatus.isBlank()) return pendingStatusText

    return if (pendingCount > 0L) {
        uploadWorkerPending(workerStatus, pendingCount)
    } else {
        uploadWorker(workerStatus)
    }
}

@Composable
fun TopEventName(
''')
text = merge_pattern.sub(new_merge, text, count=1)
upload_pattern = re.compile(r'fun uploadStatusColor\(.*?\nfun raceStatusColor\(', re.S)
if len(upload_pattern.findall(text)) != 1:
    raise SystemExit('HomeScreen: expected one upload helper block')
new_upload = textwrap.dedent('''\
fun uploadStatusColor(
    pendingUploadCount: Long,
    inRace: Boolean,
    disabledColor: Color
): Color {
    if (!inRace && pendingUploadCount == 0L) {
        return disabledColor
    }

    return when {
        pendingUploadCount <= 10L -> RegattaGreen
        pendingUploadCount <= 50L -> RegattaOrange
        else -> RegattaRed
    }
}

fun shortUploadStatus(
    pendingUploadCount: Long,
    inRace: Boolean,
    raceStatusCode: String,
    raceDataReady: Boolean,
    raceLegalAccepted: Boolean,
    hasRaceSetup: Boolean,
    pendingText: (Long) -> String = { "$it pending" },
    blockedText: String = "blocked",
    offText: String = "off",
    waitingText: String = "waiting",
    readyText: String = "ready",
    idleText: String = "idle"
): String {
    if (!inRace) {
        if (pendingUploadCount > 0L) {
            return pendingText(pendingUploadCount)
        }

        return when {
            hasRaceSetup && !raceLegalAccepted -> blockedText
            !raceDataReady -> offText
            raceStatusCode.equals("planned", ignoreCase = true) -> waitingText
            raceStatusCode.equals("racing", ignoreCase = true) -> readyText
            raceStatusCode.equals("started", ignoreCase = true) -> readyText
            else -> idleText
        }
    }

    return if (pendingUploadCount <= 10L) "OK" else "$pendingUploadCount"
}

fun raceStatusColor(
''')
text = upload_pattern.sub(new_upload, text, count=1)
home.write_text(text)

test.write_text(textwrap.dedent('''\
package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUploadStatusTest {

    @Test
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals("37 pending", shortUploadStatus(37L, false, "finished", true, true, true))
    }

    @Test
    fun smallPendingBacklogOutsideRace_isNotCollapsedToOk() {
        assertEquals("3 pending", shortUploadStatus(3L, false, "finished", true, true, true))
    }

    @Test
    fun noRaceSetup_isOffWithoutParsingDisplayText() {
        assertEquals("off", shortUploadStatus(0L, false, "", false, false, false))
    }

    @Test
    fun loadedSetupWithoutLegalAcceptance_isBlocked() {
        assertEquals("blocked", shortUploadStatus(0L, false, "planned", false, false, true))
    }

    @Test
    fun rawPlannedStatus_isWaiting() {
        assertEquals("waiting", shortUploadStatus(0L, false, "planned", true, true, true))
    }

    @Test
    fun rawStartedStatus_isReady() {
        assertEquals("ready", shortUploadStatus(0L, false, "started", true, true, true))
    }

    @Test
    fun inRaceBacklog_usesRawPendingCount() {
        assertEquals("42", shortUploadStatus(42L, true, "racing", true, true, true))
        assertEquals("OK", shortUploadStatus(10L, true, "racing", true, true, true))
    }
}
'''))
