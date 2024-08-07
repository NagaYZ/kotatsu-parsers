package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("LUASCANS", "LuaScans", "en")
internal class LuaScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LUASCANS, "luacomic.com", pageSize = 20, searchPageSize = 10) {
	override val isTagsExclusionSupported = false
}
