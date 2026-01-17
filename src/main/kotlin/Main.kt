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
import org.intellij.lang.annotations.Language
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val seenComicsFile = File("seen_comics.json")

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

fun saveSeenComics(comics: Set<Int>) {
    seenComicsFile.writeText(json.encodeToString(comics))
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
)

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

// Format date as RFC822 GMT
fun comicToRfc822(year: String, month: String, day: String): String {
    val dt = LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), 0, 0)
    val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
    return dt.atZone(ZoneOffset.UTC).format(formatter)
}

// Compose RSS XML
fun makeRss(comic: Comic, pubDate: String): String {
    @Language("HTML") val htmlContent = """<div>
            <a href="${comic.img}">
              <img src="${comic.img}" alt="${comic.alt}" style="height: auto;" />
            </a>
            <p>[<a href="https://xkcd.com/${comic.num}/">#${comic.num}</a>] ${comic.alt}</p>
          </div>"""

    @Language("XML") val xml = """
<?xml version="1.0" encoding="UTF-8" ?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>[xkcd] Random Comic Feed</title>
    <link>https://xkcd.com/</link>
    <description>A feed of random xkcd comics.</description>
    <language>en-us</language>
    <copyright>xkcd.com</copyright>
    <lastBuildDate>$pubDate</lastBuildDate>
    <atom:link href="https://tzoral.github.io/daily-random-xkcd/docs/rss/xkcd_feed.xml" rel="self" type="application/rss+xml" />
    <item>
      <title>${comic.title}</title>
      <link>https://xkcd.com/${comic.num}/</link>
      <description>
        <![CDATA[
          $htmlContent
        ]]>
      </description>
      <guid isPermaLink="true">https://xkcd.com/${comic.num}/</guid>
      <pubDate>$pubDate</pubDate>
    </item>
  </channel>
</rss>
"""
    return xml.trimIndent()
}

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
    val pubDate = comicToRfc822(comic.year, comic.month, comic.day)
    val rssString = makeRss(comic, pubDate)

    val rssDir = Paths.get("rss/xkcd")
    withContext(Dispatchers.IO) {
        Files.createDirectories(rssDir)
        File(rssDir.resolve("feed.xml").toString()).writeText(rssString)
    }

    println("RSS feed written for XKCD#${comic.num}: ${comic.title}")
}
