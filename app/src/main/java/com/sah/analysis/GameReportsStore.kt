package com.sah.analysis

import com.sah.content.Json
import com.sah.content.boolean
import com.sah.content.double
import com.sah.content.int
import com.sah.content.objectList
import com.sah.content.string
import com.sah.content.toJsonString
import java.io.File

/**
 * Keeps the analyses the player ran by hand, so a game they studied once opens
 * with its report already on screen instead of costing another engine pass.
 *
 * One JSON object per game in `reports.ndjson`, keyed by [GameReport.id]. Writes
 * append — a crash halfway through a save keeps every earlier report — and the
 * last line for an id wins on read, so re-analysing a game replaces its report
 * without ever rewriting the file.
 */
class GameReportsStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    private val file: File get() = File(directory, "reports.ndjson")

    /** The stored report for [gameId], or null when that game was never analysed. */
    fun find(gameId: String): GameReport? {
        if (gameId.isEmpty() || !file.exists()) return null
        var found: GameReport? = null
        synchronized(LOCK) {
            file.forEachLine { line ->
                val report = reportFromJson(line.trim()) ?: return@forEachLine
                if (report.id == gameId) found = report
            }
        }
        return found
    }

    /** Every stored report, one per game: an id analysed twice keeps its latest analysis. */
    fun loadAll(): Map<String, GameReport> {
        val reports = LinkedHashMap<String, GameReport>()
        if (!file.exists()) return reports
        synchronized(LOCK) {
            file.forEachLine { line ->
                val report = reportFromJson(line.trim()) ?: return@forEachLine
                reports[report.id] = report
            }
        }
        return reports
    }

    /** Stores [report] under its own id; an empty id is not a game and is ignored. */
    fun save(report: GameReport) {
        if (report.id.isEmpty()) return
        synchronized(LOCK) {
            file.appendText(report.toJsonLine() + "\n", Charsets.UTF_8)
        }
    }

    private companion object {
        val LOCK = Any()
    }
}

/** One flat JSON object per report; the only on-disk format. */
fun GameReport.toJsonLine(): String {
    val fields = ArrayList<String>(8)
    fields += "\"id\":${id.toJsonString()}"
    fields += "\"opening\":${opening.toJsonString()}"
    fields += "\"white\":$whiteAccuracy"
    fields += "\"black\":$blackAccuracy"
    fields += "\"playerIsWhite\":$playerIsWhite"
    fields += "\"takeaway\":${takeaway.toJsonString()}"
    fields += "\"source\":${source.toJsonString()}"
    fields += "\"badges\":${MoveBadges.encodeBadges(badges).toJsonString()}"
    fields += "\"moments\":[${moments.joinToString(",") { it.toJsonObject() }}]"
    return fields.joinToString(",", "{", "}")
}

private fun ReportMoment.toJsonObject(): String = buildString {
    append("{\"moveNumber\":$moveNumber")
    append(",\"moverWhite\":$moverWhite")
    append(",\"san\":${san.toJsonString()}")
    append(",\"bestSan\":${bestSan.toJsonString()}")
    append(",\"category\":${category.name.toJsonString()}")
    append(",\"loss\":$loss")
    append(",\"lesson\":${lesson.toJsonString()}")
    append("}")
}

/** Parses one stored line; null when the line is corrupt or incomplete. */
fun reportFromJson(line: String): GameReport? = runCatching {
    val map = Json.parseObject(line)
    GameReport(
        id = map.string("id") ?: return null,
        opening = map.string("opening") ?: "",
        whiteAccuracy = map.double("white") ?: return null,
        blackAccuracy = map.double("black") ?: return null,
        playerIsWhite = map.boolean("playerIsWhite") ?: return null,
        takeaway = map.string("takeaway") ?: "",
        source = map.string("source") ?: "",
        badges = MoveBadges.parseBadges(map.string("badges")),
        moments = map.objectList("moments").mapNotNull { momentFromJson(it) },
    )
}.getOrNull()

private fun momentFromJson(map: Map<String, Any?>): ReportMoment? {
    val rawCategory = map.string("category") ?: return null
    return ReportMoment(
        moveNumber = map.int("moveNumber") ?: return null,
        moverWhite = map.boolean("moverWhite") ?: false,
        san = map.string("san") ?: return null,
        bestSan = map.string("bestSan") ?: "",
        category = runCatching { MoveCategory.valueOf(rawCategory) }.getOrNull() ?: return null,
        loss = map.double("loss") ?: 0.0,
        lesson = map.string("lesson") ?: "",
    )
}
