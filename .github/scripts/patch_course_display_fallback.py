from pathlib import Path

course = Path('app/src/main/java/de/williserv/regattaclient/CourseScreen.kt')
test = Path('app/src/test/java/de/williserv/regattaclient/CourseProgressMarkStateTest.kt')


def replace_once(path, old, new):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one match, found {count}')
    path.write_text(text.replace(old, new, 1))


replace_once(
    course,
    '''                } else {
                    courseMarkDisplayItems(raceMarksText, stringResource(R.string.marks_prefix)).forEach { item ->
''',
    '''                } else {
                    val localizedSkippedMarker = stringResource(R.string.mark_skipped_compact, 0, "")
                        .removePrefix("0")
                        .trim()
                    courseMarkDisplayItems(
                        raceMarksText = raceMarksText,
                        marksPrefix = stringResource(R.string.marks_prefix),
                        skippedMarker = localizedSkippedMarker
                    ).forEach { item ->
'''
)

replace_once(
    course,
    '''fun courseMarkDisplayItems(
    raceMarksText: String,
    marksPrefix: String = "Marks:"
): List<CourseMarkDisplayItem> {
''',
    '''fun courseMarkDisplayItems(
    raceMarksText: String,
    marksPrefix: String = "Marks:",
    skippedMarker: String = "[skipped]"
): List<CourseMarkDisplayItem> {
'''
)

replace_once(
    course,
    '''        .map { raw ->
            val skipped = raw.contains("[skipped]", ignoreCase = true)
            val label = raw
                .replace("[skipped]", "", ignoreCase = true)
                .trim()

            CourseMarkDisplayItem(
''',
    '''        .map { raw ->
            val skipped = skippedMarker.isNotBlank() &&
                    raw.contains(skippedMarker, ignoreCase = true)
            val label = if (skipped) {
                raw.replace(skippedMarker, "", ignoreCase = true).trim()
            } else {
                raw
            }

            CourseMarkDisplayItem(
'''
)

text = test.read_text()
addition = '''
    @Test
    fun localizedDisplayFallbackRecognizesLocalizedSkippedMarker() {
        val items = courseMarkDisplayItems(
            raceMarksText = "Bahnmarken: 1 Tonne [übersprungen], 2 Tonne",
            marksPrefix = "Bahnmarken:",
            skippedMarker = "[übersprungen]"
        )

        assertEquals("1 Tonne", items[0].label)
        assertTrue(items[0].skipped)
        assertEquals("2 Tonne", items[1].label)
        assertFalse(items[1].skipped)
    }
'''
if not text.endswith('\n}\n'):
    raise SystemExit('unexpected test file ending')
test.write_text(text[:-3] + addition + '\n}\n')
