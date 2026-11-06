@file:OptIn(ExperimentalTime::class)

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format.char
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Reports the occupancy of Kotlin build playgrounds ("PGs") on TeamCity: for every playground project it shows the branch its versioned settings point at, how long ago those settings changed, the latest build and a guess whether the playground is currently taken by someone.

// tokens are restricted to read only the stuff needed, so do not worry
enum class TcServer(val address: String, private val tokenFile: String) {
    BUILDSERVER("https://buildserver.labs.intellij.net", ".bst"),
    KOTLINLANG("https://kotlinlang.teamcity.com", ".klt");

    val token: String by lazy {
        val file = File(tokenFile)
        check(file.isFile) { "Token file '${file.absolutePath}' not found: put a TeamCity access token there." }
        file.readLines().first().trim()
    }
}




fun printPlaygrounds(server: TcServer, projectIds: List<String>) {
    println("Analyzing ${projectIds.size} playground(s) on ${server.name} (${server.address})...")

    val results = projectIds.map { projectId ->
        projectId to runCatching { getPgInfo(server, projectId) }
            .onSuccess { println("$projectId analyzed") }
            .onFailure { println("$projectId FAILED: ${it.message}") }
    }
    val playgrounds = results.mapNotNull { (_, result) -> result.getOrNull() }

    println("\n=============== ${server.name} PLAYGROUNDS (${playgrounds.size} of ${projectIds.size}) ===============")


    val counts = playgrounds.groupingBy { it.prediction }.eachCount()
    println("Summary: " + Prediction.entries.filter { it in counts }.joinToString(", ") { "${it.value}: ${counts[it]}" })

    val failures = results.mapNotNull { (projectId, result) -> result.exceptionOrNull()?.let { projectId to it } }
    if (failures.isNotEmpty()) {
        println("\nNot analyzed (${failures.size}):")
        failures.forEach { (projectId, error) -> println("* $projectId -- ${error.message ?: error}") }
    }
    println()

    playgrounds.sortedBy { it.prediction.priority }.forEach(::println)

}

fun getPgInfo(server: TcServer, projectId: String): PgInfo {
    val project = server.getXml("/app/rest/projects/$projectId")

    // the branch is stored in the VCS root that holds the versioned settings of the playground
    val branch = project.property("rootId")
        ?.let { server.getXml("/app/rest/vcs-roots/id:$it").property("branch") }
        ?: "unknown"

    val status = server.get("/app/rest/projects/$projectId/versionedSettings/status")
    val updatedAt = parseXml(status).documentElement.attr("timestamp")
    val updatedAgo = updatedAt?.let { parseHumanReadableTime(it).date.daysUntil(today) }
    val builds = server.getLatestBuilds(projectId)

    return PgInfo(
        name = project.documentElement.attr("name") ?: projectId,
        prediction = predictStatus(builds.firstOrNull()?.ago, updatedAgo),
        url = "${server.address}/project/$projectId",
        branch = branch,
        age = updatedAgo,
        updatedAt = updatedAt,
        commit = "last change\\s'(.+?)'".toRegex().find(status)?.groupValues?.get(1),
        builds = builds,
    )
}

// The most recent finished build of every build configuration of the playground, newest first.
private fun TcServer.getLatestBuilds(projectId: String): List<MyBuild> {
    val fields = "buildType(id,name,builds(" +
            "\$locator(running:false,canceled:false,count:1,branch:(default:any))," +
            "build(triggered(user),number,status,startDate)))"

    return getXml("/app/rest/buildTypes?locator=affectedProject:(id:$projectId)&fields=$fields")
        .elements("buildType")
        .map { buildType ->
            val build = buildType.child("build")
            MyBuild(
                name = buildType.attr("name"),
                triggeredBy = build?.child("triggered")?.child("user")?.attr("name"),
                status = build?.attr("status"),
                startDate = build?.child("startDate")?.textContent?.take(15)?.let(::parseTimeOrNull),
            )
        }
        .sortedByDescending { it.startDate }
        .toList()
}

// Entry order is the report order: from the safest playground to take to the one most likely occupied.
enum class Prediction(val priority: Int, val value: String) {
    FREE_FRESH(0, "Free"),
    FREE_OLD(3, "Free (but old)"),
    PROBABLY_FREE(2, "Probably Free"),
    MOST_LIKELY_FREE(1, "Most Likely Free"),
    PROBABLY_BUSY(4, "Probably Busy"),
    BUSY(5, "Busy"),
    UNKNOWN(6, "Unknown"),
}

// Ages are in days; the fresher of the two decides, as any activity means someone is still around.
private fun predictStatus(latestBuildAge: Int?, vcsAge: Int?): Prediction {
    if (latestBuildAge == null || vcsAge == null) return Prediction.UNKNOWN
    return when (minOf(latestBuildAge, vcsAge)) {
        in Int.MIN_VALUE..7 -> Prediction.BUSY
        in 60..Int.MAX_VALUE -> Prediction.FREE_OLD
        in 30..59 -> Prediction.FREE_FRESH
        in 15..29 -> Prediction.MOST_LIKELY_FREE
        // both are between 8 and 14 (including) days
        else -> if (latestBuildAge < vcsAge) Prediction.PROBABLY_FREE else Prediction.PROBABLY_BUSY
    }
}

data class PgInfo(
    val name: String,
    val prediction: Prediction,
    val url: String,
    val branch: String,
    val age: Int?,
    val updatedAt: String?,
    val commit: String?,
    val builds: List<MyBuild>
) {
    override fun toString(): String = """
        ============= PG $name: ${prediction.value} ===============
        Link: $url
        Branch: $branch
        Last updated: $age days ago ($updatedAt)
        Revision: $commit
        Latest Build: ${builds.firstOrNull() ?: "None"}
        =====================================

        """.trimIndent()
}

data class MyBuild(
    val name: String?,
    val triggeredBy: String?,
    val status: String?,
    val startDate: LocalDateTime?
) {
    val ago = startDate?.date?.daysUntil(today)

    override fun toString() =
        "$ago days ago ($startDate) ${triggeredBy?.let { "by $it " }.orEmpty()} '$name' with result $status"
}

// ===== TeamCity REST =====

private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

private fun TcServer.get(path: String): String {
    val request = Request.Builder().url("$address$path").addHeader("Authorization", "Bearer $token").build()
    client.newCall(request).execute().use { response ->
        val body = response.body.string()
        check(response.isSuccessful) { "GET $address$path returned \"${response.code}\" with body \"$body\"" }
        return body
    }
}

private fun TcServer.getXml(path: String): Document = parseXml(get(path))

// ===== time =====

private val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

// TeamCity timestamps like "20251210T101228" (the trailing offset is expected to be cut off)
private val tcTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day(); char('T'); hour(); minute(); second()
}

private fun parseTimeOrNull(time: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(time, tcTimeFormat) }.getOrNull()

// human-readable datetime like "Wed Dec 10 10:12:28 UTC 2025"
private val humanTimeFormat = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)

// normalized to UTC to avoid system timezone ambiguity
private fun parseHumanReadableTime(time: String): LocalDateTime =
    ZonedDateTime.parse(time, humanTimeFormat).withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime().toKotlinLocalDateTime()

// ===== XML =====

private val xmlFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }

private fun parseXml(xml: String): Document = xmlFactory.newDocumentBuilder().parse(xml.byteInputStream())

private fun NodeList.elements(): Sequence<Element> = (0 until length).asSequence().mapNotNull { item(it) as? Element }

private fun Document.elements(tag: String): Sequence<Element> = getElementsByTagName(tag).elements()

private fun Element.elements(tag: String): Sequence<Element> = getElementsByTagName(tag).elements()

private fun Element.child(tag: String): Element? = elements(tag).firstOrNull()

private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

// value of the first <property name="..."> in the document
private fun Document.property(name: String): String? =
    elements("property").firstOrNull { it.getAttribute("name").equals(name, ignoreCase = true) }?.attr("value")


fun main() = PLAYGROUNDS.forEach { (server, projectIds) -> printPlaygrounds(server, projectIds) }