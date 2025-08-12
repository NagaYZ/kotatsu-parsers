package org.koitharu.kotatsu.parsers.site.en

// Removed serialization imports
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("READCOMICONLINE", "ReadComicOnline", "en", ContentType.COMICS)
internal class ReadComicOnline(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.READCOMICONLINE, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("readcomiconline.li")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getGenreList(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)


	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			!filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty() -> {
				val url = "https://$domain/AdvanceSearch".toHttpUrl().newBuilder().apply {
					addQueryParameter("comicName", filter.query.orEmpty().trim())
					addQueryParameter("page", page.toString())

					if (filter.states.isNotEmpty()) {
						val status = when {
							filter.states.contains(MangaState.FINISHED) -> "Completed"
							filter.states.contains(MangaState.ONGOING) -> "Ongoing"
							else -> ""
						}
						addQueryParameter("status", status)
					}

					if (filter.tags.isNotEmpty()) {
						addQueryParameter("ig", filter.tags.joinToString(",") { it.key })
					}

					if (filter.tagsExclude.isNotEmpty()) {
						addQueryParameter("eg", filter.tagsExclude.joinToString(",") { it.key })
					}
				}.build()

				val doc = webClient.httpGet(url).parseHtml()
				parseMangaList(doc)
			}

			else -> {
				val path = when (order) {
					SortOrder.POPULARITY -> "ComicList/MostPopular"
					SortOrder.UPDATED -> "ComicList/LatestUpdate"
					SortOrder.NEWEST -> "ComicList/Newest"
					SortOrder.ALPHABETICAL -> "ComicList"
					else -> "ComicList/MostPopular"
				}

				val url = "https://$domain/$path?page=$page"
				val doc = webClient.httpGet(url).parseHtml()
				parseMangaList(doc)
			}
		}
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.item-list div.section.group.list").map { element ->
			val linkElement = element.selectFirstOrThrow("div.col.info p:first-child a")
			val href = linkElement.attrAsRelativeUrl("href")
			val coverElement = element.selectFirstOrThrow("div.col.cover a img")

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = linkElement.text(),
				coverUrl = coverElement.attr("src").toAbsoluteUrl(domain),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
				contentRating = null,
				largeCoverUrl = null,
				description = null,
				chapters = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val infoSection = doc.selectFirstOrThrow("div.section.group div.col.info")

		val genres = infoSection.select("p:has(span:contains(Genres:)) > a").mapNotNullToSet { a ->
			MangaTag(
				key = a.text(),
				title = a.text(),
				source = source,
			)
		}

		// Check if "Mature" is in genres to determine content rating
		val contentRating = if (genres.any { it.title.equals("Mature", ignoreCase = true) }) {
			ContentRating.ADULT
		} else {
			ContentRating.SAFE
		}

		val descriptionSection = doc.select("div.section.group").getOrNull(1)
		val description = descriptionSection?.select("p")?.text()

		return manga.copy(
			title = doc.selectFirstOrThrow("div.heading h3").text(),
			altTitles = emptySet(),
			authors = infoSection.select("p:has(span:contains(Writer:)) > a").eachText().toSet(),
			description = description,
			tags = genres,
			state = parseStatus(infoSection.select("p:has(span:contains(Status:))").first()?.text().orEmpty()),
			coverUrl = doc.selectFirst("div.col.cover img")?.absUrl("src") ?: manga.coverUrl,
			contentRating = contentRating,
			chapters = doc.selectFirst("ul.list")?.select("li")?.mapChapters(reversed = true) { i, element ->
				val urlElement = element.selectFirstOrThrow("div.col-1 a")
				val dateText = element.selectFirst("div.col-2 span")?.text()

				MangaChapter(
					id = generateUid(urlElement.attr("href")),
					title = urlElement.text(),
					number = i + 1f,
					volume = 0,
					url = urlElement.attrAsRelativeUrl("href"),
					scanlator = null,
					uploadDate = parseDateOrNull(dateText),
					branch = null,
					source = source,
				)
			} ?: emptyList(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		TODO()
	}

	private fun parseStatus(status: String): MangaState? = when {
		status.contains("Ongoing") -> MangaState.ONGOING
		status.contains("Completed") -> MangaState.FINISHED
		else -> null
	}

	private fun parseDateOrNull(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0
		return try {
			dateFormat.parse(dateStr)?.time ?: 0
		} catch (_: Exception) {
			0
		}
	}

	private fun getGenreList(): Set<MangaTag> {
		val genres = mapOf(
			"1" to "Action",
			"2" to "Adventure",
			"38" to "Anthology",
			"46" to "Anthropomorphic",
			"41" to "Biography",
			"49" to "Children",
			"3" to "Comedy",
			"17" to "Crime",
			"19" to "Drama",
			"25" to "Family",
			"20" to "Fantasy",
			"31" to "Fighting",
			"5" to "Graphic Novels",
			"28" to "Historical",
			"15" to "Horror",
			"35" to "Leading Ladies",
			"51" to "LGBTQ",
			"44" to "Literature",
			"40" to "Manga",
			"4" to "Martial Arts",
			"8" to "Mature",
			"33" to "Military",
			"56" to "Mini-Series",
			"47" to "Movies & TV",
			"55" to "Music",
			"23" to "Mystery",
			"21" to "Mythology",
			"48" to "Personal",
			"42" to "Political",
			"43" to "Post-Apocalyptic",
			"27" to "Psychological",
			"39" to "Pulp",
			"53" to "Religious",
			"9" to "Robots",
			"32" to "Romance",
			"52" to "School Life",
			"16" to "Sci-Fi",
			"50" to "Slice of Life",
			"54" to "Sport",
			"30" to "Spy",
			"22" to "Superhero",
			"24" to "Supernatural",
			"29" to "Suspense",
			"18" to "Thriller",
			"34" to "Vampires",
			"37" to "Video Games",
			"26" to "War",
			"45" to "Western",
			"36" to "Zombies",
		)

		return genres.entries.mapToSet { (key, title) ->
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}
}
