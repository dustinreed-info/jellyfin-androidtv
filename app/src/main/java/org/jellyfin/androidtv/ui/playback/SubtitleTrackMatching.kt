package org.jellyfin.androidtv.ui.playback

import java.util.Locale
import java.util.MissingResourceException

/**
 * Picks the player text track for the Jellyfin subtitle stream at [position] among [streamLanguages], the embedded
 * subtitle streams in container order. Uses the same position when both lists line up and the language agrees;
 * otherwise (a track the player cannot read, or extra caption tracks) the stream's position among streams of its own
 * language. Returns the index into [trackLanguages], or null when nothing matches.
 */
fun matchSubtitleTrack(streamLanguages: List<String?>, position: Int, trackLanguages: List<String?>): Int? {
	if (position !in streamLanguages.indices) return null
	val wanted = normalizeLanguage(streamLanguages[position])
	val samePosition = trackLanguages.getOrNull(position)?.let(::normalizeLanguage)
	if (position < trackLanguages.size && (wanted == null || samePosition == null || samePosition == wanted)) {
		if (streamLanguages.size == trackLanguages.size || wanted == null) return position
	}

	if (wanted != null) {
		val rank = streamLanguages.take(position).count { normalizeLanguage(it) == wanted }
		val match = trackLanguages.withIndex().filter { normalizeLanguage(it.value) == wanted }.getOrNull(rank)
		if (match != null) return match.index
	}
	return position.takeIf { it < trackLanguages.size }
}

// ISO 639-2 bibliographic codes (common in Matroska) mapped to the terminology codes Locale reports.
private val bibliographicCodes = mapOf(
	"alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld",
	"fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri",
	"may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
)

/** Jellyfin reports "eng"/"ger", Media3 "en"/"de": compare both as ISO 639-2/T. */
private fun normalizeLanguage(language: String?): String? {
	val code = language?.trim()?.substringBefore('-')?.lowercase(Locale.ROOT)
		?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
	bibliographicCodes[code]?.let { return it }
	return try {
		Locale(code).isO3Language.ifEmpty { code }
	} catch (_: MissingResourceException) {
		code
	}
}
