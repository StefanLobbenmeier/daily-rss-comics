import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

val seenComicsFile = File("seen_comics.json")

// Basic helper to read JSON array of numbers, or empty if missing/invalid
fun loadSeenComics(): MutableSet<Int> {
    return if (seenComicsFile.exists()) {
        val content = seenComicsFile.readText()
        Regex("""\d+""").findAll(content).map { it.value.toInt() }.toMutableSet()
    } else {
        mutableSetOf()
    }
}

fun saveSeenComics(comics: Set<Int>) {
    seenComicsFile.writeText(comics.joinToString(prefix = "[", postfix = "]"))
}

// HTTP GET helper
fun httpGetJson(urlStr: String): String? {
    try {
        val url = URL(urlStr)
        with(url.openConnection() as HttpURLConnection) {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            inputStream.bufferedReader().use { return it.readText() }
        }
    } catch (e: Exception) {
        println("HTTP GET failed: $e")
        return null
    }
}

// Fetch the number of the latest XKCD comic
fun fetchLatestComicNumber(): Int? {
    val json = httpGetJson("https://xkcd.com/info.0.json") ?: return null
    return Regex("""["']num["']\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
}

// Get a random comic (with retries), skipping previously seen
fun fetchRandomComic(seen: Set<Int>, latestComic: Int, maxAttempts: Int = 10): Pair<Int, String>? {
    repeat(maxAttempts) {
        val randNum = Random.nextInt(1, latestComic + 1)
        if (randNum in seen) return@repeat
        val json = httpGetJson("https://xkcd.com/$randNum/info.0.json")
        if (json != null && "\"num\":" in json) return randNum to json
        Thread.sleep(1000)
    }
    println("Could not get unseen comic after $maxAttempts attempts.")
    return null
}

// Parse comic fields from JSON (minimal, regex-based)
data class Comic(val num: Int, val title: String, val img: String, val alt: String, val year: String, val month: String, val day: String)

fun parseComic(json: String): Comic? {
    fun field(name: String): String =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1) ?: ""
    val num = Regex(""""num"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return Comic(num, field("title"), field("img"), field("alt"), field("year"), field("month"), field("day"))
}

// Format date as RFC822 GMT
fun comicToRfc822(year: String, month: String, day: String): String {
    val dt = LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), 0, 0)
    val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
    return dt.atZone(ZoneOffset.UTC).format(formatter)
}

// Compose RSS XML
fun makeRss(comic: Comic, pubDate: String): String = """
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
          <div>
            <a href="${comic.img}">
              <img src="${comic.img}" alt="${comic.alt}" style="height: auto;" />
            </a>
            <p>[<a href="https://xkcd.com/${comic.num}/">#${comic.num}</a>] ${comic.alt}</p>
          </div>
        ]]>
      </description>
      <guid isPermaLink="true">https://xkcd.com/${comic.num}/</guid>
      <pubDate>$pubDate</pubDate>
    </item>
  </channel>
</rss>
""".trimIndent()

fun main() {
    // MAIN SEQUENCE
    val seenComics = loadSeenComics()
    val latestNum = fetchLatestComicNumber()
    if (latestNum == null) {
        println("Could not fetch the latest xkcd.")
        return
    }
    if (seenComics.size >= latestNum) {
        println("All comics seen, resetting.")
        seenComics.clear()
        saveSeenComics(seenComics)
    }

    val fetched = fetchRandomComic(seenComics, latestNum)
    if (fetched == null) {
        println("Could not get a comic.")
        return
    }
    val (comicNum, comicJson) = fetched
    val comic = parseComic(comicJson) ?: throw Exception("Failed to parse comic json")

    seenComics.add(comic.num)
    saveSeenComics(seenComics)
    val pubDate = comicToRfc822(comic.year, comic.month, comic.day)
    val rssString = makeRss(comic, pubDate)

// Ensure 'docs/rss' exists, then write file there
    val rssDir = Paths.get("docs/rss")
    Files.createDirectories(rssDir)
    File(rssDir.resolve("xkcd_feed.xml").toString()).writeText(rssString)

    println("RSS feed written for XKCD#${comic.num}: ${comic.title}")
}
