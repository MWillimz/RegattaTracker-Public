from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {actual}: {old!r}")
    p.write_text(text.replace(old, new), encoding="utf-8")

# Compose must observe raw race-status changes.
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/MainActivity.kt",
    "import androidx.compose.runtime.mutableStateOf\n",
    "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue\n",
)
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/MainActivity.kt",
    '    private var currentRaceStatus = ""\n',
    '    private var currentRaceStatus by mutableStateOf("")\n',
)
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/MainActivity.kt",
    "                            raceStatusCode = currentRaceStatus,\n                            raceLegalAccepted = raceLegalAccepted.value,\n",
    "                            raceStatusCode = currentRaceStatus,\n                            raceStatusDisplayText = raceStatusText.value,\n                            raceLegalAccepted = raceLegalAccepted.value,\n",
)

# Home keeps raw state for logic, but may render the localized fetch error when data is unavailable.
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt",
    "    raceStatusCode: String,\n    raceLegalAccepted: Boolean,\n",
    "    raceStatusCode: String,\n    raceStatusDisplayText: String,\n    raceLegalAccepted: Boolean,\n",
)
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt",
    "                raceStatusCode = raceStatusCode,\n                raceStartText = raceStartText,\n                inRace = inRace,\n                startPrefix = startPrefix,\n",
    "                raceStatusCode = raceStatusCode,\n                raceStatusDisplayText = raceStatusDisplayText,\n                raceDataReady = raceDataReady,\n                raceStartText = raceStartText,\n                inRace = inRace,\n                startPrefix = startPrefix,\n",
)
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt",
    "fun shortRaceStatusText(\n    raceStatusCode: String,\n    raceStartText: String,\n    inRace: Boolean,\n",
    "fun shortRaceStatusText(\n    raceStatusCode: String,\n    raceStatusDisplayText: String = \"\",\n    raceDataReady: Boolean = true,\n    raceStartText: String,\n    inRace: Boolean,\n",
)
replace_exact(
    "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt",
    "    val cleaned = raceStatusCode.trim()\n\n    if (cleaned.equals(\"finished\", ignoreCase = true)) return finishedText\n",
    "    val cleaned = raceStatusCode.trim()\n\n    if (!raceDataReady) {\n        return raceStatusDisplayText\n            .removePrefix(racePrefix)\n            .trim()\n            .ifBlank { notActiveText }\n    }\n\n    if (cleaned.equals(\"finished\", ignoreCase = true)) return finishedText\n",
)

# Regression coverage for unavailable/error display without using display strings for logic.
test_path = "app/src/test/java/de/williserv/regattaclient/HomeUploadStatusTest.kt"
p = Path(test_path)
text = p.read_text(encoding="utf-8")
insert_before = "}\n"
new_test = '''\n    @Test\n    fun unavailableRaceData_rendersLocalizedDisplayErrorInsteadOfStaleRawStatus() {\n        assertEquals(\n            \"Fehler 503\",\n            shortRaceStatusText(\n                raceStatusCode = \"racing\",\n                raceStatusDisplayText = \"Regatta: Fehler 503\",\n                raceDataReady = false,\n                raceStartText = \"Start: 2026-09-01T12:00:00Z\",\n                inRace = false,\n                racePrefix = \"Regatta:\"\n            )\n        )\n    }\n'''
if new_test.strip() in text:
    raise SystemExit(f"{test_path}: regression test already present")
if not text.endswith(insert_before):
    raise SystemExit(f"{test_path}: unexpected class ending")
p.write_text(text[:-2] + new_test + "}\n", encoding="utf-8")

# German grammar.
replace_exact("app/src/main/res/values-de/strings.xml", "Hash des Ausschreibunges fehlt", "Hash der Ausschreibung fehlt")
replace_exact("app/src/main/res/values-de/strings.xml", "Veranstaltungsidentität des Ausschreibunges fehlt", "Veranstaltungsidentität der Ausschreibung fehlt")

# Italian article/grammar cleanup.
for old, new in [
    ("accetta l’bando di regata", "accetta il bando di regata"),
    ("Accetta bando", "Accetta il bando"),
    ("L’bando di regata è vuoto", "Il bando di regata è vuoto"),
    ("Hash dell’bando di regata mancante", "Hash del bando di regata mancante"),
    ("Identità evento dell’bando di regata mancante", "Identità dell’evento del bando di regata mancante"),
    ("L’bando di regata è cambiato", "Il bando di regata è cambiato"),
    ("Accettazione bando di regata", "Accettazione del bando di regata"),
]:
    replace_exact("app/src/main/res/values-it/strings.xml", old, new)

# Feminine race-status wording.
replace_exact("app/src/main/res/values-fr/strings.xml", '<string name="status_not_active">non actif</string>', '<string name="status_not_active">non active</string>')
replace_exact("app/src/main/res/values-it/strings.xml", '<string name="status_not_active">non attivo</string>', '<string name="status_not_active">non attiva</string>')
replace_exact("app/src/main/res/values-es/strings.xml", '<string name="status_not_active">no activo</string>', '<string name="status_not_active">no activa</string>')

# English fallback labels only; endpoint paths remain unchanged.
replace_exact("app/src/main/res/values/strings.xml", '<string name="impressum">Impressum</string>', '<string name="impressum">Legal Notice</string>')
replace_exact("app/src/main/res/values/strings.xml", '<string name="datenschutz">Datenschutz</string>', '<string name="datenschutz">Privacy Policy</string>')
replace_exact("app/src/main/res/values/strings.xml", '<string name="server_impressum">Server Impressum</string>', '<string name="server_impressum">Server Legal Notice</string>')
replace_exact("app/src/main/res/values/strings.xml", '<string name="server_datenschutz">Server Datenschutz</string>', '<string name="server_datenschutz">Server Privacy Policy</string>')

# Pin the corrected wording in resource tests.
loc_test = Path("app/src/test/java/de/williserv/regattaclient/LocalizationResourcesTest.kt")
text = loc_test.read_text(encoding="utf-8")
replace_pairs = [
    (
        '        assertEquals("frei", resources.getString(R.string.clear_status))\n',
        '        assertEquals("frei", resources.getString(R.string.clear_status))\n        assertEquals("Hash der Ausschreibung fehlt", resources.getString(R.string.race_notice_hash_missing))\n',
    ),
    (
        '        assertEquals("Bouée — omise", resources.getString(R.string.mark_skipped, "Bouée"))\n',
        '        assertEquals("Bouée — omise", resources.getString(R.string.mark_skipped, "Bouée"))\n        assertEquals("non active", resources.getString(R.string.status_not_active))\n',
    ),
    (
        '        assertEquals("Tracciamento manuale interrotto", resources.getString(R.string.manual_tracking_stopped))\n',
        '        assertEquals("Tracciamento manuale interrotto", resources.getString(R.string.manual_tracking_stopped))\n        assertEquals("Il bando di regata è vuoto", resources.getString(R.string.race_notice_empty))\n        assertEquals("non attiva", resources.getString(R.string.status_not_active))\n',
    ),
    (
        '        assertEquals("Envío", resources.getString(R.string.upload))\n',
        '        assertEquals("Envío", resources.getString(R.string.upload))\n        assertEquals("no activa", resources.getString(R.string.status_not_active))\n',
    ),
    (
        '        assertEquals("Race: not loaded", resources.getString(R.string.race_not_loaded))\n',
        '        assertEquals("Race: not loaded", resources.getString(R.string.race_not_loaded))\n        assertEquals("Legal Notice", resources.getString(R.string.impressum))\n        assertEquals("Privacy Policy", resources.getString(R.string.datenschutz))\n',
    ),
]
for old, new in replace_pairs:
    actual = text.count(old)
    if actual != 1:
        raise SystemExit(f"LocalizationResourcesTest.kt: expected 1 occurrence, found {actual}: {old!r}")
    text = text.replace(old, new)
loc_test.write_text(text, encoding="utf-8")

print("PR72 final hardening patch applied successfully")
