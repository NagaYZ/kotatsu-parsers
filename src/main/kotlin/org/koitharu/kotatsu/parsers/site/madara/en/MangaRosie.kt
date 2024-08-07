package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGAROSIE", "Toon69", "en")
internal class MangaRosie(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAROSIE, "toon69.com", pageSize = 16) {
	override val datePattern = "MMMM dd, yyyy"
}
