package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*


@MangaSourceParser("MANGAHERE", "MangaHere")
internal class Mangahere(context: MangaLoaderContext) : PagedMangaParser(context, MangaSource.MANGAHERE, 60, 12) {

	override val configKeyDomain = ConfigKey.Domain("www.mangahere.cc","m.mangahere.cc")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val availableStates: Set<MangaState> =	EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
	override val isMultipleTagsSupported = false



	override suspend fun getListPage(page: Int, filter: MangaListFilter?): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when (filter) {
				is MangaListFilter.Search -> {
					append("/search")
					append(filter.query.urlEncoded())
				}

				is MangaListFilter.Advanced -> {
					append("/")
					val genre = filter.tags.oneOrThrowIfMany()?.key ?: "directory"
					append("$genre/")

					when (filter.sortOrder) {
						SortOrder.POPULARITY -> append("")
						SortOrder.UPDATED -> append("?latest")
						SortOrder.RATING -> append("?rating")
						SortOrder.ALPHABETICAL -> append("?az")
						SortOrder.NEWEST -> append("?news")
						else -> append("")
					}
					append("comics?page=")
					append(page.toString())
				}

				null -> {
					append("/directory/$page.htm")
				}
			}
		}
		println(url)
		return parseMangaList(webClient.httpGet(url).parseHtml())

	}

	private val selectMangaList = ".manga-list-1-list li"
	private val selectMangaNextPage = "div.pager-list-left a:last-child"
	private val selectSearchManga = ".manga-list-4-list > li"

	private fun parseMangaList(docs: Document): List<Manga> {
		//TODO search
		return docs.select(selectMangaList).map {
			val a = it.selectFirstOrThrow("a")
			val url = a.attrAsRelativeUrl("href")
			val title = a.attr("title")
			val coverUrl = it.selectFirstOrThrow("img.manga-list-1-cover").src().orEmpty()
			Manga(
				id = generateUid(url),
				url = a.attrAsRelativeUrl("href"),
				title = title,
				altTitle = null,
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				isNsfw = isNsfwSource,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				author = null,
				source = source,
			)
		}
	}
	private val selectChapterList = "ul.detail-main-list > li"

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = doc.select(selectChapterList)
		val genreElements = doc.select(".detail-info-right-tag-list > a")
		val tags = genreElements.mapToSet {
			MangaTag(
				key = it.attr("href").substringAfter("directory/").substringBeforeLast('/'),
				title = it.attr("title"),
				source = source,
			)
		}
		return manga.copy(
			tags = tags,
			author = doc.selectFirstOrThrow(".detail-info-right-say > a").text(),
			description = doc.selectFirstOrThrow(".fullcontent").text(),
			state = when (doc.selectFirstOrThrow("span.detail-info-right-title-tip").text().lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"complete" -> MangaState.FINISHED
				else -> null
			},
			chapters = chapters.mapChapters(reversed = true) { i, li 	->
				val chapter = li.selectFirstOrThrow("a")
				val uploadDate = li.select("a p.title2").first()?.text()?.let { parseChapterDate(it) }
				val chapterUrl = chapter.attr("href")
				MangaChapter(
					id = generateUid(chapterUrl),
					name = chapter.select("a p.title3").first()!!.text().trim(),
					number = i+1,
					url = chapterUrl,
					scanlator = null,
					uploadDate = uploadDate ?: 0L,
					branch = null,
					source = source,
				)
			},
		)

	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		//TODO fix this
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		println(fullUrl)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		println(doc)
		return doc.select(".chapter-container img").map { img ->
			val url = img.src()?.toRelativeUrl(domain) ?: img.parseFailed("Image src not found")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/directory").parseHtml()
		return doc.select(".update-bar-filter-list-title ~ ul > li > a").mapNotNullToSet { a ->
			val title = a.text()
			if (title != "All") {
				MangaTag(
					key = a.attr("href").substringAfter('/').substringBeforeLast('/'),
					title = title,
					source = source,
				)
			} else {
				null
			}
		}
	}

	private fun parseChapterDate(date: String): Long {
		return if ("Today" in date || " ago" in date) {
			Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		} else if ("Yesterday" in date) {
			Calendar.getInstance().apply {
				add(Calendar.DATE, -1)
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		} else {
			try {
				SimpleDateFormat("MMM dd,yyyy", Locale.ENGLISH).parse(date)?.time ?: 0L
			} catch (e: ParseException) {
				0L
			}
		}
	}
}
