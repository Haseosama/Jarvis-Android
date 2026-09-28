package com.jarvis.android.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSearchTest {
    private val videos = listOf(
        PhoneVideo(3, "Anniversaire_Mamie.mp4", "Camera", 3_000, 65_000),
        PhoneVideo(2, "VID_20260912.mp4", "Anniversaires", 2_000, 5_000),
        PhoneVideo(1, "plage.mov", "Vacances été", 1_000, 3_725_000),
    )

    @Test fun `a video is found by the words of its name or album, accents and case aside`() {
        assertEquals(listOf(3L, 2L), matchVideos(videos, "la vidéo de l'anniversaire").map { it.id })
        assertEquals(listOf(3L), matchVideos(videos, "anniversaire de MAMIE").map { it.id })
        assertEquals(listOf(1L), matchVideos(videos, "les vacances d'ete").map { it.id })
        assertEquals(emptyList<Long>(), matchVideos(videos, "anniversaire plage").map { it.id })
    }

    @Test fun `the extension is not a word, and no words keeps every video`() {
        assertEquals(emptyList<Long>(), matchVideos(videos, "mov").map { it.id })
        assertEquals(3, matchVideos(videos, "mes vidéos").size)
        assertEquals(3, matchVideos(videos, "").size)
    }

    @Test fun `a length is said in seconds, minutes or hours`() {
        assertEquals("5 s", durationWords(5_400))
        assertEquals("1 min 05", durationWords(65_000))
        assertEquals("2 min", durationWords(120_000))
        assertEquals("1 h 02", durationWords(3_725_000))
    }
}
