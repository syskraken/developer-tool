package dev.franklin.devbridge.analysis

/**
 * Works out which screens a phone has. Android names them two ways: a small logical id (0, 1, ...)
 * that `input` and `am start` take, and a long physical id that `screencap` and `screenrecord`
 * take. The two are tied together by the display's `uniqueId` ("local:<physical id>").
 */
object DisplayParser {

    class Choice(
        /** Id for `input -d` and `am start --display`. */
        val logicalId: Int,
        /** Id for `screencap -d`; null for the main screen (the default) or when it could not be worked out. */
        val physicalId: String?,
        val name: String,
        val width: Int?,
        val height: Int?,
        /** True when the logical id was inferred from ordering rather than read from the phone. */
        val guessed: Boolean = false,
    ) {
        fun label(): String {
            val size = if (width != null && height != null) " · ${width}×$height" else ""
            val note = if (guessed) " (id guessed)" else ""
            return "$name$size — display $logicalId$note"
        }
    }

    private class Logical(val id: Int, val name: String, val physicalId: String?, val width: Int?, val height: Int?)

    /** Physical ids from `dumpsys SurfaceFlinger --display-id`, in the order the phone lists them. */
    fun parsePhysical(text: String): List<Pair<String, String>> {
        val regex = Regex("""Display (\d+) \(HWC display \d+\)(?:: port=\d+)?(?:.*?displayName="([^"]*)")?""")
        return text.lineSequence().mapNotNull { line ->
            regex.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.distinctBy { it.first }.toList()
    }

    private fun parseLogical(text: String): List<Logical> {
        val out = LinkedHashMap<Int, Logical>()
        for (line in text.lineSequence()) {
            if (!line.contains("DisplayInfo{")) continue
            val id = Regex("""displayId (\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (out.containsKey(id)) continue
            val rawName = Regex("""DisplayInfo\{"([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            val name = rawName.replace(Regex(""",?\s*displayId \d+"""), "").trim().ifEmpty { "Display $id" }
            val physical = Regex("""uniqueId "local:(\d+)"""").find(line)?.groupValues?.get(1)
            val size = Regex("""real (\d+) x (\d+)""").find(line)
            out[id] = Logical(id, name, physical, size?.groupValues?.get(1)?.toIntOrNull(), size?.groupValues?.get(2)?.toIntOrNull())
        }
        return out.values.toList()
    }

    /**
     * Builds the list to pick from. Uses the display manager's own report when it has one; otherwise
     * falls back to the physical ids alone, numbering them in order as a best guess.
     */
    fun choices(displayDump: String, surfaceFlingerDump: String): List<Choice> {
        val physical = parsePhysical(surfaceFlingerDump)
        val logical = parseLogical(displayDump).filter { it.physicalId != null }

        if (logical.isNotEmpty()) {
            return logical.sortedBy { it.id }.map { l ->
                Choice(
                    logicalId = l.id,
                    // The main screen is what screencap shows with no id, so leave it blank.
                    physicalId = if (l.id == 0) null else l.physicalId,
                    name = l.name,
                    width = l.width,
                    height = l.height,
                )
            }
        }

        return physical.mapIndexed { index, (id, name) ->
            Choice(
                logicalId = index,
                physicalId = if (index == 0) null else id,
                name = name.ifEmpty { if (index == 0) "Main screen" else "Screen ${index + 1}" },
                width = null,
                height = null,
                guessed = true,
            )
        }
    }
}
