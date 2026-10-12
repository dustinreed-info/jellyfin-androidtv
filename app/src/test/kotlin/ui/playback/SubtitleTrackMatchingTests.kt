package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class SubtitleTrackMatchingTests : FunSpec({
	// ASS (burned in by the server) + SRT ita + SRT eng: the player still exposes all three container tracks.
	val streams = listOf("ita", "ita", "eng")

	test("English is the third container track even when the ASS track is burned in") {
		matchSubtitleTrack(streams, 2, listOf("it", "it", "en")) shouldBe 2
		matchSubtitleTrack(streams, 1, listOf("it", "it", "en")) shouldBe 1
	}

	test("a track the player cannot read shifts positions, so the language decides") {
		// The player dropped the first (unreadable) track.
		matchSubtitleTrack(streams, 2, listOf("it", "en")) shouldBe 1
	}

	test("an extra caption track does not shift the match") {
		matchSubtitleTrack(listOf("eng", "ita"), 1, listOf("en", "it", "und")) shouldBe 1
		matchSubtitleTrack(listOf("eng", "ita"), 1, listOf(null, "en", "it")) shouldBe 2
	}

	test("unknown languages fall back to position") {
		matchSubtitleTrack(listOf(null, null), 1, listOf(null, null)) shouldBe 1
		matchSubtitleTrack(listOf("eng"), 3, listOf("en")).shouldBeNull()
		matchSubtitleTrack(listOf("eng"), 0, emptyList()).shouldBeNull()
	}
})
