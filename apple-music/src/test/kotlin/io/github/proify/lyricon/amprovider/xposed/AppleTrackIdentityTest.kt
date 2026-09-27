package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleTrackIdentityTest {
    @Test fun standardIdWins() = assertEquals("1", AppleTrackIdentity.mediaId("1", "2"))
    @Test fun appleMetadataKeySuppliesMissingStandardId() = assertEquals("1709253464", AppleTrackIdentity.mediaId(null, "1709253464"))
    @Test fun blankStandardIdUsesAppleId() = assertEquals("2", AppleTrackIdentity.mediaId(" ", "2"))
    @Test fun blankIdsDoNotCreateTrack() = assertNull(AppleTrackIdentity.mediaId(null, " "))
    @Test fun currentLyricsArePublished() = assertTrue(AppleTrackIdentity.shouldPublish("2", "2", false))
    @Test fun latePreviousSongIsNotPublished() = assertFalse(AppleTrackIdentity.shouldPublish("2", "1", false))
    @Test fun duplicateLyricsAreNotPublished() = assertFalse(AppleTrackIdentity.shouldPublish("2", "2", true))
    @Test fun unboundLyricsAreNotPublished() = assertFalse(AppleTrackIdentity.shouldPublish(null, "2", false))
}
