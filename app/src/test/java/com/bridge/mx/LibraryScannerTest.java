package com.bridge.mx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The MediaStore library gate and download-tree scope, pinned against the exact
 * filenames found on the device (2026-10-14).
 *
 * <p>The gate decides which Download files become "present" rows. It must admit
 * release-named episodes ("Show - 03"), while still rejecting numbered
 * NON-episodes (music videos, pronunciation drills, recipes) so they can never
 * be matched to an anime.
 */
public class LibraryScannerTest {

    // ------------------------------------------------------- episode marker

    @Test
    public void releaseStyleEpisodesAreAdmitted() {
        String[] real = {
            "Shikkoku no Shaga The Animation - 01 [1080p-HEVC][hstream.moe][v2].mkv",
            "Immoral Game Master The Animation - 02 [1080p-HEVC][hstream.moe][v2].mkv",
            "Mayoiga no Onee-san The Animation - 01 [1080p-HEVC][hstream.moe][v2].mkv",
            "Joshi Luck! - 06 [1080p-HEVC][hstream.moe].mkv",
            "[Rhythm] Youjo Shenki 2 - 07 [1080p AAC][1C7B9C0A].mkv",
            "[Rhythm] Youjo Shenki - 12 END [BD 1080p Hi10p FLAC][1DD524AE].mkv",
            "[Piyoko] Sousou no Frieren - Marumaru no Mahou (Mini Anime) - 23 [WEB 1080p YT VP9 AAC 2.0] [0DA09CC1].mkv",
            "Show - 03.mkv",
            "Show - 03 1080p.mkv",
        };
        for (String name : real) {
            assertTrue("should be admitted: " + name, LibraryScanner.hasEpisodeMarker(name));
        }
    }

    @Test
    public void numberedNonEpisodesAreRejected() {
        String[] noise = {
            "Bunny Girl Senpai Ending _ Fukashigi no Carte - 8D AUDIO   Lyrics(1080P_60FPS).mp4",
            "Phonetic Transcriptions Test (IPA) _ 100 Questions _ English Pronunciation _ Phonetic Alphabet.webm",
            "[FULL_GAME VER] D_N_A _ 25\u6642.mp4",
            "His recipe for this eclair cake. 6 eggs,____1 c sugar,__4c milk.mp4",
        };
        for (String name : noise) {
            assertFalse("must be rejected: " + name, LibraryScanner.hasEpisodeMarker(name));
        }
    }

    @Test
    public void existingMarkersStillAdmitted() {
        String[] real = {
            "\u0627\u0644\u062d\u0644\u0642\u0629 2 Kanata kara.mp4", // Arabic
            "Attack on Titan - EP 06.mkv",
            "Naruto Episode 12.mp4",
            "Show Name S02E07.mkv",
        };
        for (String name : real) {
            assertTrue("should be admitted: " + name, LibraryScanner.hasEpisodeMarker(name));
        }
    }

    @Test
    public void nullIsNotAnEpisode() {
        assertFalse(LibraryScanner.hasEpisodeMarker(null));
    }

    // ---------------------------------------------------------- folder scope

    @Test
    public void modernRelativePathAcceptsBothFolderNames() {
        assertTrue(LibraryScanner.isDownloadTree("/download/video/x.mkv", ""));
        assertTrue(LibraryScanner.isDownloadTree("/downloads/video/x.mkv", ""));
        assertTrue(LibraryScanner.isDownloadTree("/download/x.mkv", ""));
    }

    @Test
    public void modernRelativePathRejectsOtherTrees() {
        assertFalse(LibraryScanner.isDownloadTree("/movies/x.mkv", ""));
        assertFalse(LibraryScanner.isDownloadTree("/other/download/x.mkv", ""));
        assertFalse(LibraryScanner.isDownloadTree("/dcim/downloaded/x.mkv", ""));
    }

    @Test
    public void legacyAbsolutePathAcceptsBothFolderNames() {
        String base = "/storage/emulated/0";
        assertTrue(LibraryScanner.isDownloadTree(base + "/download/x.mkv", base));
        assertTrue(LibraryScanner.isDownloadTree(base + "/downloads/x.mkv", base));
        assertFalse(LibraryScanner.isDownloadTree(base + "/pictures/x.mkv", base));
    }
}
