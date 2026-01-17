import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.text.StringSubstitutor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.temporal.TemporalAccessor
import kotlin.random.Random

val rssDir = Paths.get("rss/xkcd")

val rssTemplate = getStringResource("xkcd/feed.xml")
val itemXmlTemplate = getStringResource("xkcd/item.xml")
val itemHtmlTemplate = getStringResource("xkcd/item.html")

val json = Json {
    prettyPrint = true
    isLenient = true
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
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

suspend fun fetchLatestComic(): Comic {
    return client.get("https://xkcd.com/info.0.json").body<Comic>()
}

// Get a random comic (with retries), skipping previously seen
suspend fun fetchComics(latestComic: Comic): List<Comic> {
    val comics = mutableListOf<Comic>()


    generateSequence {
        // random comic index, exclude index of latest comic because we already have that one
        Random.nextInt(1, latestComic.num)
    }.distinct()
        .take(9)
        .asFlow()
        .map { index -> client.get("https://xkcd.com/$index/info.0.json").body<Comic>() }
        .collect { comic -> comics.add(comic) }

    comics.add(latestComic)

    return comics
}

private fun formatRfc822(dt: TemporalAccessor): String {
    return  RFC_1123_DATE_TIME.format(dt)
}

// Compose RSS XML
fun makeRss(comics: List<Comic>): String {
    val itemsXml = comics.map(::comicToItemXml).joinToString("\n")

    return rssTemplate
        .replace("\$items", itemsXml)
        .replace("\$buildDate", formatRfc822(ZonedDateTime.now()))
}

private fun comicToItemXml(comic: Comic): String {
    val stringSubstitutor = StringSubstitutor({ key ->
        when (key) {
            "comic.img" -> comic.img
            "comic.title" -> comic.title
            "comic.alt" -> comic.alt
            "comic.num" -> comic.num.toString()
            "comic.date" -> formatRfc822(comic.date.atStartOfDay(ZoneOffset.UTC))
            else -> throw Exception("Unknown key $key")
        }
    })

    val itemHtml = stringSubstitutor.replace(itemHtmlTemplate)
    val itemXml = stringSubstitutor.replace(itemXmlTemplate)
        .replace($$"$htmlContent", itemHtml)
    return itemXml
}

private fun getStringResource(path: String): String =
    Comic::class.java.getResourceAsStream(path)!!.reader().readText()

suspend fun main() {
    val latestComic = fetchLatestComic()
    val comics = fetchComics(latestComic)

    val rssString = makeRss(comics)

    withContext(Dispatchers.IO) {
        Files.createDirectories(rssDir)
        File(rssDir.resolve("feed.xml").toString()).writeText(rssString)
    }

    comics.forEach { comic ->
        println("RSS feed written for XKCD#${comic.num}: ${comic.title}")
    }
}
