package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

//Images blocked by ReCAPTCHA
@MangaSourceParser("MERLINSCANS", "MerlinScans", "tr")
internal class MerlinScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaSource.MERLINSCANS, "merlinscans.com", 10)
