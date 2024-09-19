package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArrayMap
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHWA18", "Manhwa18.net", "en", type = ContentType.HENTAI)
internal class Manhwa18Parser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWA18, pageSize = 18, searchPageSize = 18) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwa18.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.NEWEST,
			SortOrder.RATING,
		)

	private val tagsMap = SuspendLazy(::parseTags)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
		availableContentRating = emptySet(),
	)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://$domain/uploads/logos/logo-mini.png", 92, null),
			),
			domain,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem?page=")
			append(page.toString())

			when {
				!filter.query.isNullOrEmpty() -> {
					append("&q=")
					append(filter.query.urlEncoded())
				}

				else -> {

					append("&accept_genres=")
					if (filter.tags.isNotEmpty()) {
						append(
							filter.tags.joinToString(",") { it.key },
						)
					}

					append("&reject_genres=")
					if (filter.tagsExclude.isNotEmpty()) {
						append(
							filter.tagsExclude.joinToString(",") { it.key },
						)
					}

					append("&sort=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "az"
							SortOrder.ALPHABETICAL_DESC -> "za"
							SortOrder.POPULARITY -> "top"
							SortOrder.UPDATED -> "update"
							SortOrder.NEWEST -> "new"
							SortOrder.RATING -> "like"
							else -> null
						},
					)

					filter.states.oneOrThrowIfMany()?.let {
						append("&status=")
						append(
							when (it) {
								MangaState.ONGOING -> "1"
								MangaState.FINISHED -> "3"
								MangaState.PAUSED -> "2"
								else -> ""
							},
						)
					}
				}
			}
		}

		val docs = webClient.httpGet(url).parseHtml()

		return docs.select(".card-body .thumb-item-flow")
			.map {
				val titleElement = it.selectFirstOrThrow(".thumb_attr.series-title > a")
				val absUrl = titleElement.attrAsAbsoluteUrl("href")
				Manga(
					id = generateUid(absUrl.toRelativeUrl(domain)),
					title = titleElement.text(),
					altTitle = null,
					url = absUrl.toRelativeUrl(domain),
					publicUrl = absUrl,
					rating = RATING_UNKNOWN,
					isNsfw = true,
					coverUrl = it.selectFirst("div.img-in-ratio")?.attrAsAbsoluteUrl("data-bg").orEmpty(),
					tags = emptySet(),
					state = null,
					author = null,
					largeCoverUrl = null,
					description = null,
					source = MangaParserSource.MANHWA18,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val cardInfoElement = docs.selectFirst("div.series-information")
		val author = cardInfoElement?.selectFirst(".info-name:contains(Author)")?.parent()
			?.select("a")
			?.joinToString(", ") { it.text() }
		val availableTags = tagsMap.get()
		val tags = cardInfoElement?.selectFirst(".info-name:contains(Genre)")?.parent()
			?.select("a")
			?.mapNotNullToSet { availableTags[it.text().lowercase(Locale.ENGLISH)] }
		val state = cardInfoElement?.selectFirst(".info-name:contains(Status)")?.parent()
			?.selectFirst("a")
			?.let {
				when (it.text().lowercase()) {
					"on going" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"on hold" -> MangaState.PAUSED
					else -> null
				}
			}

		return manga.copy(
			altTitle = cardInfoElement?.selectFirst("b:contains(Other names)")?.parent()?.ownText()?.removePrefix(": "),
			author = author,
			description = docs.selectFirst(".series-summary .summary-content")?.html(),
			tags = tags.orEmpty(),
			state = state,
			chapters = docs.select(".card-body > .list-chapters > a").mapChapters(reversed = true) { index, element ->
				val chapterUrl = element.attrAsAbsoluteUrlOrNull("href")?.toRelativeUrl(domain)
					?: return@mapChapters null
				val uploadDate = parseUploadDate(element.selectFirst(".chapter-time")?.text())
				MangaChapter(
					id = generateUid(chapterUrl),
					name = element.selectFirst(".chapter-name")?.text().orEmpty(),
					number = index + 1f,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = MangaParserSource.MANHWA18,
				)
			},
		)
	}

	// 7 minutes ago
	// 5 hours ago
	// 2 days ago
	// 2 weeks ago
	// 4 years ago
	private fun parseUploadDate(timeStr: String?): Long {
		timeStr ?: return 0

		val timeWords = timeStr.split(' ')
		if (timeWords.size != 3) return 0
		val timeWord = timeWords[1]
		val timeAmount = timeWords[0].toIntOrNull() ?: return 0
		val timeUnit = when (timeWord) {
			"minute", "minutes" -> Calendar.MINUTE
			"hour", "hours" -> Calendar.HOUR
			"day", "days" -> Calendar.DAY_OF_YEAR
			"week", "weeks" -> Calendar.WEEK_OF_YEAR
			"month", "months" -> Calendar.MONTH
			"year", "years" -> Calendar.YEAR
			else -> return 0
		}
		val cal = Calendar.getInstance()
		cal.add(timeUnit, -timeAmount)
		return cal.time.time
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		return doc.requireElementById("chapter-content").select("img").mapNotNull {
			val url = it.attrAsRelativeUrlOrNull("data-src")
				?: it.attrAsRelativeUrlOrNull("src")
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = MangaParserSource.MANHWA18,
			)
		}
	}

	private suspend fun parseTags(): Map<String, MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem?q=").parseHtml()
		val list = doc.getElementsByAttribute("data-genre-id")
		if (list.isEmpty()) {
			return emptyMap()
		}
		val result = ArrayMap<String, MangaTag>(list.size)
		for (item in list) {
			val id = item.attr("data-genre-id")
			val name = item.text()
			result[name.lowercase(Locale.ENGLISH)] = MangaTag(
				title = name.toTitleCase(Locale.ENGLISH),
				key = id,
				source = source,
			)
		}
		return result
	}
}
