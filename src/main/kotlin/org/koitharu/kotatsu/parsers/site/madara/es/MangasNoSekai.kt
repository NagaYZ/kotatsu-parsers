package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGASNOSEKAI", "MangasNoSekai", "es")
internal class MangasNoSekai(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGASNOSEKAI, "mangasnosekai.com")
