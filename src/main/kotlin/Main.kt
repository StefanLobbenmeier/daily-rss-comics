import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.text.StringSubstitutor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.temporal.TemporalAccessor
import java.util.Locale

val rssDir = Paths.get("rss/xkcd")
val seenComicsFile = rssDir.resolve("seen.json").toFile()

val json = Json {
    prettyPrint = true
    isLenient = true
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
    }
}

fun loadSeenComics(): MutableSet<Int> {
    return if (seenComicsFile.exists()) {
        val content = seenComicsFile.inputStream().reader().readText()
        json.decodeFromString<MutableSet<Int>>(content)
    } else {
        mutableSetOf()
    }
}

suspend fun saveSeenComics(comics: Set<Int>) {
    withContext(Dispatchers.IO) {
        Files.createDirectories(rssDir)
        seenComicsFile.writeText(json.encodeToString(comics))
    }
}

@Serializable
data class Comic(
    val month: String,
    val num: Int,
    val link: String,
    val year: String,
    val news: String,
    val safe_title: String,
    val transcript: String,
    val alt: String,
    val img: String,
    val title: String,
    val day: String,
) {
    val date: LocalDate get() = LocalDate.of(year.toInt(), month.toInt(), day.toInt())

}

suspend fun fetchLatestComicNumber(): Int {
    val latestComic = client.get("https://xkcd.com/info.0.json").body<Comic>()
    return latestComic.num
}

// Get a random comic (with retries), skipping previously seen
suspend fun fetchRandomComic(seen: Set<Int>, latestComic: Int): Comic {
    val indexToFetch = List(latestComic) { index ->
        index
    }.filter { it !in seen }
        .shuffled()
        .first()

    return client.get("https://xkcd.com/$indexToFetch/info.0.json").body<Comic>()
}

private fun formatRfc822(dt: TemporalAccessor): String {
    return  RFC_1123_DATE_TIME.format(dt)
}

// Compose RSS XML
fun makeRss(comic: Comic, pubDate: String): String {
    val rssTemplate = getStringResource("xkcd/feed.xml")
    val itemXmlTemplate = getStringResource("xkcd/item.xml")
    val itemHtmlTemplate = getStringResource("xkcd/item.html")

    val stringSubstitutor = StringSubstitutor({ key -> when(key) {
        "comic.img" -> comic.img
        "comic.title" -> comic.title
        "comic.alt" -> comic.alt
        "comic.num" -> comic.num.toString()
        "comic.date" -> formatRfc822(comic.date.atStartOfDay(ZoneOffset.UTC))
        else -> throw Exception("Unknown key $key")
    } })

    val itemHtml = stringSubstitutor.replace(itemHtmlTemplate)
    val itemXml = stringSubstitutor.replace(itemXmlTemplate)
        .replace("\$htmlContent", itemHtml)
    return rssTemplate
        .replace("\$items", itemXml)
        .replace("\$pubDate", pubDate)
}

private fun getStringResource(path: String): String =
    Comic::class.java.getResourceAsStream(path)!!.reader().readText()

suspend fun main() {
    var seenComics = loadSeenComics()
    val latestNum = fetchLatestComicNumber()

    if (seenComics.size >= latestNum) {
        println("All comics seen, resetting.")
        seenComics = mutableSetOf()
        saveSeenComics(seenComics)
    }

    val comic = fetchRandomComic(seenComics, latestNum)

    seenComics.add(comic.num)
    saveSeenComics(seenComics)
    val pubDate = formatRfc822(ZonedDateTime.now())
    val rssString = makeRss(comic, pubDate)

    withContext(Dispatchers.IO) {
        Files.createDirectories(rssDir)
        File(rssDir.resolve("feed.xml").toString()).writeText(rssString)
    }

    println("RSS feed written for XKCD#${comic.num}: ${comic.title}")
}
