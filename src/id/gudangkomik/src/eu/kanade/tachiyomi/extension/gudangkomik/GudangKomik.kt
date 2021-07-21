package eu.kanade.tachiyomi.extension.id.komikindoid

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class GudangKomik : ParsedHttpSource() {
    override val name = "GudangKomik"
    override val baseUrl = "https://gudangkomik.com"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/list/comic/hot?$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/list/comic/terbaru?$page", headers)
    }

    override fun popularMangaSelector() = "div.grid.grid-rows-3"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.relative.inline-flex"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("div.row-span-2.flex.justify-center img").attr("src")
        manga.title = element.select("div.h-44.text-left h3").text()
        element.select("div.h-44.text-left > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
        }
        return manga
    }

    override fun searchMangaRequest(page: Int, query: String): Request {
        val builtUrl = if (page == 1) "$baseUrl/list/comic/search?" else "$baseUrl/list/comic/search?page=$page"
        val url = builtUrl.toHttpUrlOrNull()!!.newBuilder()
        url.addQueryParameter("q", query)
        url.addQueryParameter("page", page.toString())
        }
        return GET(url.build().toString(), headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infoanime").first()
        val descElement = document.select("div.desc > .entry-content.entry-content-single").first()
        val sepName = infoElement.select(".infox > .spe > span:nth-child(2)").last()
        val manga = SManga.create()
        // need authorCleaner to take "pengarang:" string to remove it from author
        val authorCleaner = document.select(".infox .spe b:contains(Pengarang)").text()
        manga.author = document.select(".infox .spe span:contains(Pengarang)").text().substringAfter(authorCleaner)
        manga.artist = manga.author
        val genres = mutableListOf<String>()
        infoElement.select(".infox > .genre-info > a").forEach { element ->
            val genre = element.text()
            genres.add(genre)
        }
        manga.genre = genres.joinToString(", ")
        manga.status = parseStatus(infoElement.select(".infox > .spe > span:nth-child(1)").text())
        manga.description = descElement.select("p").text()
        manga.thumbnail_url = document.select(".thumb > img:nth-child(1)").attr("src")
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.toLowerCase().contains("berjalan") -> SManga.ONGOING
        element.toLowerCase().contains("tamat") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapter_list li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select(".lchx a").first()
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select(".dt a").first()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    fun parseChapterDate(date: String): Long {
        return if (date.contains("lalu")) {
            val value = date.split(' ')[0].toInt()
            when {
                "detik" in date -> Calendar.getInstance().apply {
                    add(Calendar.SECOND, value * -1)
                }.timeInMillis
                "menit" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, value * -1)
                }.timeInMillis
                "jam" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, value * -1)
                }.timeInMillis
                "hari" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * -1)
                }.timeInMillis
                "minggu" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * 7 * -1)
                }.timeInMillis
                "bulan" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, value * -1)
                }.timeInMillis
                "tahun" in date -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, value * -1)
                }.timeInMillis
                else -> {
                    0L
                }
            }
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    chapter.chapter_number = it.groups[1]?.value!!.toFloat()
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        var i = 0
        document.select("div.imgch img").forEach { element ->
            val url = element.attr("src")
            i++
            if (url.isNotEmpty()) {
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageRequest(page: Page): Request {
        if (page.imageUrl!!.contains("komikcdn.me")) {
            val headers = Headers.Builder()
            headers.apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3")
            }
            return GET(page.imageUrl!!, headers.build())
        } else {
            val imgHeader = Headers.Builder().apply {
                add("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 Mobile Safari/537.36")
                add("Referer", baseUrl)
            }.build()
            return GET(page.imageUrl!!, imgHeader)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")
}
