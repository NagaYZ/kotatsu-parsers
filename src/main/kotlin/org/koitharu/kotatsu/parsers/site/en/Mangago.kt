package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Request
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGAGO", "Mangago", "en", ContentType.HENTAI)
internal class Mangago(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaSource.MANGAGO, 48, 10) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.UPDATED)
	override val availableStates: Set<MangaState> = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
	override val configKeyDomain = ConfigKey.Domain("mangago.me")
	override val isTagsExclusionSupported = true
	private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

	override suspend fun getListPage(page: Int, filter: MangaListFilter?): List<Manga> {
		val url = buildString {
			append("https://$domain")
			when (filter) {

				is MangaListFilter.Search -> {
					append("/r/l_search/?name=")
					append(filter.query.urlEncoded())
					append("&page=$page")
				}

				is MangaListFilter.Advanced -> {
					append("/genre/")
					val tags = if (filter.tags.isEmpty()) "all" else filter.tags.joinToString(",") { it.key.urlEncoded() }
					append(tags)
					append("/$page/")
					val stateUrl = StringBuilder("?f=1&o=1")
					filter.states.forEach { state ->
						when (state) {
							MangaState.ONGOING -> stateUrl.setCharAt(stateUrl.indexOf("f=1") + 2, '0')
							MangaState.FINISHED -> stateUrl.setCharAt(stateUrl.indexOf("o=1") + 2, '0')
							else -> {}
						}
					}
					append(stateUrl)

					filter.sortOrder.let {
						append("&sortby=")
						append(
							when (it) {
								SortOrder.UPDATED -> "update_date"
								SortOrder.POPULARITY -> "view"
								SortOrder.NEWEST -> "create_date"
								else -> ""
							},
						)
					}

					val excludedTags = filter.tagsExclude.joinToString(",") { it.key }
					append("&e=$excludedTags")
				}

				null -> append("/genre/all/$page")
			}
		}
		println(url)
		return webClient.httpGet(url).parseHtml().parseMangaList()
	}



	private fun Document.parseMangaList(): List<Manga> {
		return select(selectMangaList).map {
			val mangaUrl = it.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(mangaUrl),
				url = mangaUrl,
				publicUrl = mangaUrl.toAbsoluteUrl(domain),
				title = it.attr("title"),
				coverUrl = it.selectFirstOrThrow("img").attr("data-src").ifEmpty { it.selectFirstOrThrow("img").attr("src") },
				source = source,
				altTitle = null,
				largeCoverUrl = null,
				author = null,
				isNsfw = false,
				rating = RATING_UNKNOWN,
				state = null,
				tags = emptySet(),
			)
		}
	}

	private val selectChapterList = "table#chapter_table > tbody > tr, table.uk-table > tbody > tr"
	private val selectMangaList = ".thm-effect"

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = doc.select(selectChapterList)
		val genreElements = doc.select("label:contains(Genre(s)) ~ a")
		val tag = genreElements.mapToSet {
			MangaTag(
				key = it.text(),
				title = it.text(),
				source = source,
			)
		}
		return manga.copy(
			tags = tag,
			author = doc.selectFirstOrThrow(".manga_right label:contains(Author) + a, li:contains(Author(s)) > a").text(),
			description = doc.selectFirstOrThrow(".manga_summary").text(),
			chapters = chapters.mapChapters(reversed = true) { i, tr 	->
				val chapter = tr.selectFirstOrThrow("a.chico")
				val uploadDate = dateFormat.parse(tr.select("td:last-child").text().trim())?.time
				val chapterUrl = chapter.attr("href")
				MangaChapter(
					id = generateUid(chapterUrl),
					name = chapter.text().trim(),
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

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val url = "https://$domain/genre/"
		val doc = webClient.httpGet(url).parseHtml()
		val genreElements = doc.select("a.genre_select_div")
		return genreElements.mapToSet { element ->
			val genreName = element.attr("_id")
			MangaTag(
				key = genreName,
				title = genreName,
				source = source,
			)
		}
	}


	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		//TODO: fix decryption
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(fullUrl).parseHtml()
		val imgsrcsScript = document.selectFirst("script:containsData(imgsrcs)")?.html()
			?: throw Exception("Could not find imgsrcs")
		val imgsrcRaw = imgSrcsRegex.find(imgsrcsScript)?.groupValues?.get(1)
			?: throw Exception("Could not extract imgsrcs")
		val imgsrcs = context.decodeBase64(imgsrcRaw)

		val chapterJsUrl = document.getElementsByTag("script").first {
			it.attr("src").contains("chapter.js", ignoreCase = true)
		}.attr("abs:src")

		val request = Request.Builder().url(chapterJsUrl).build()
		val obfuscatedChapterJs = context.httpClient.newCall(request).execute().body!!.string()

		val deobfChapterJs = SoJsonV4Deobfuscator.decode(obfuscatedChapterJs)

		val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
		val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		val keyS = SecretKeySpec(key, aes)
		cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))

		var imageList = cipher.doFinal(imgsrcs).toString(Charsets.UTF_8)

		try {
			val keyLocations = keyLocationRegex.findAll(deobfChapterJs).map {
				it.groupValues[1].toInt()
			}.distinct()

			val unscrambleKey = keyLocations.map {
				imageList[it].toString().toInt()
			}.toList()

			keyLocations.forEachIndexed { idx, it ->
				imageList = imageList.removeRange(it - idx..it - idx)
			}

			imageList = imageList.unscramble(unscrambleKey)
		} catch (e: NumberFormatException) {
			// Only call where it should throw is imageList[it].toString().toInt().
			// This usually means that the list is already unscrambled.
		}

		val cols = deobfChapterJs
			.substringAfter("var widthnum=heightnum=")
			.substringBefore(";")

		return imageList
			.split(",")
			.mapIndexed { idx, it ->
				val url = if (it.contains("cspiclink")) {
					"$it#desckey=${getDescramblingKey(deobfChapterJs, it)}&cols=$cols"
				} else {
					it
				}

				MangaPage(idx.toLong(),  url, null, source)
			}
	}

	private fun findHexEncodedVariable(input: String, variable: String): String {
		val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
		return regex.find(input)?.groupValues?.get(1) ?: ""
	}

	private fun String.unscramble(keys: List<Int>): String {
		var s = this
		keys.reversed().forEach {
			for (i in s.length - 1 downTo it) {
				if (i % 2 != 0) {
					val temp = s[i - it]
					s = s.replaceRange(i - it..i - it, s[i].toString())
					s = s.replaceRange(i..i, temp.toString())
				}
			}
		}
		return s
	}


	private fun String.decodeHex(): ByteArray {
		check(length % 2 == 0) { "Must have an even length" }

		return chunked(2)
			.map { it.toInt(16).toByte() }
			.toByteArray()
	}

	private suspend fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
		val imgkeys = deobfChapterJs
			.substringAfter("var renImg = function(img,width,height,id){")
			.substringBefore("key = key.split(")
			.split("\n")
			.filter { jsFilters.all { filter -> !it.contains(filter) } }
			.joinToString("\n")
			.replace("img.src", "url")

		val js = """
            function getDescramblingKey(url) { $imgkeys; return key; }
            getDescramblingKey("$imageUrl");
        """.trimIndent()
		return context.evaluateJs(js)!!

	}


	private val jsFilters =
		listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")

	private val hashCipher = "AES/CBC/ZEROBYTEPADDING"

	private val aes = "AES"

	private val keyLocationRegex by lazy {
		Regex("""str\.charAt\(\s*(\d+)\s*\)""")
	}

	private val imgSrcsRegex by lazy {
		Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
	}

	object SoJsonV4Deobfuscator {
		private val splitRegex: Regex = Regex("""[a-zA-Z]+""")

		fun decode(jsf: String): String {
			if (!jsf.startsWith("['sojson.v4']")) {
				throw IllegalArgumentException("Obfuscated code is not sojson.v4")
			}

			val args = jsf.substring(240, jsf.length - 59).split(splitRegex)

			return args.map { it.toInt().toChar() }.joinToString("")
		}
	}
}
