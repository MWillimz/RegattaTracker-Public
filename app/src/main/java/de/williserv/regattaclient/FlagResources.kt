package de.williserv.regattaclient

import org.json.JSONObject

data class RaceStartFlags(
    val className: String = "",
    val prep: String = "",
    val x: Boolean = false,
    val firstSubstitute: Boolean = false,
    val ap: Boolean = false,
    val n: Boolean = false
)

sealed class VisibleRaceFlag {
    data class ClassFlag(
        val label: String
    ) : VisibleRaceFlag()

    data class ImageFlag(
        val code: String,
        val drawableResId: Int
    ) : VisibleRaceFlag()
}

fun parseRaceStartFlags(json: JSONObject): RaceStartFlags {
    val startFlags = json.optJSONObject("start_flags") ?: return RaceStartFlags()

    return RaceStartFlags(
        className = startFlags.optString("class", "").trim(),
        prep = startFlags.optString("prep", "").trim(),
        x = startFlags.optBoolean("x", false),
        firstSubstitute = startFlags.optBoolean("1st", false),
        ap = startFlags.optBoolean("ap", false),
        n = startFlags.optBoolean("n", false)
    )
}

fun visibleRaceFlags(
    flags: RaceStartFlags,
    millisToStart: Long?
): List<VisibleRaceFlag> {
    if (flags.n) {
        return imageFlags("n")
    }

    if (flags.ap) {
        return imageFlags("ap")
    }

    val secondsToStart = millisToStart?.div(1000L)

    if (secondsToStart == null) {
        val signalCodes = mutableListOf<String>()

        if (flags.x) {
            signalCodes.add("x")
        }

        if (flags.firstSubstitute) {
            signalCodes.add("1st")
        }

        return signalCodes
            .take(3)
            .mapNotNull { code ->
                flagDrawableForCode(code)?.let { drawable ->
                    VisibleRaceFlag.ImageFlag(
                        code = code.lowercase().trim(),
                        drawableResId = drawable
                    )
                }
            }
    }

    val codes = mutableListOf<String>()

    if (secondsToStart in 1..300 && flags.className.isNotBlank()) {
        codes.add("class")
    }

    if (secondsToStart in 61..240 && flags.prep.isNotBlank()) {
        codes.add(flags.prep)
    }

    if (secondsToStart <= 0) {
        if (flags.x) {
            codes.add("x")
        }

        if (flags.firstSubstitute) {
            codes.add("1st")
        }
    }

    return codes
        .take(3)
        .mapNotNull { code ->
            if (code.equals("class", ignoreCase = true)) {
                VisibleRaceFlag.ClassFlag(flags.className)
            } else {
                flagDrawableForCode(code)?.let { drawable ->
                    VisibleRaceFlag.ImageFlag(
                        code = code.lowercase().trim(),
                        drawableResId = drawable
                    )
                }
            }
        }
}

fun flagDrawableForCode(code: String): Int? {
    return when (code.lowercase().trim()) {
        "p" -> R.drawable.flag_p
        "i" -> R.drawable.flag_i
        "z" -> R.drawable.flag_z
        "u" -> R.drawable.flag_u
        "black" -> R.drawable.flag_black

        "x" -> R.drawable.flag_x
        "1st" -> R.drawable.flag_1st
        "ap" -> R.drawable.flag_ap
        "n" -> R.drawable.flag_n

        else -> null
    }
}

private fun imageFlags(
    vararg codes: String
): List<VisibleRaceFlag> {
    return codes.mapNotNull { code ->
        flagDrawableForCode(code)?.let { drawable ->
            VisibleRaceFlag.ImageFlag(
                code = code.lowercase().trim(),
                drawableResId = drawable
            )
        }
    }
}