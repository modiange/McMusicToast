package com.diange.mcmusic.media;

/**
 * Immutable snapshot of a single system-media track.
 *
 * @param title       track title (never null, "Unknown" if missing)
 * @param artist      track artist (never null, "Unknown" if missing)
 * @param album       track album (may be empty)
 * @param source      human-readable source label
 * @param cover       cover-art URL (may be empty)
 * @param stableId    stable identifier used for dedup / toast tokens
 * @param playing     true when the source is actively playing (playbackRate > 0)
 * @param positionMs  current playback position in milliseconds (0 if unknown)
 * @param durationMs  total track duration in milliseconds (0 if unknown)
 */
public record TrackInfo(String title, String artist, String album, String source,
                        String cover, String stableId,
                        boolean playing, long positionMs, long durationMs) {

    /** Backwards-compatible constructor — playing=true, position/duration=0. */
    public TrackInfo(String title, String artist, String album, String source,
                     String cover, String stableId) {
        this(title, artist, album, source, cover, stableId, true, 0L, 0L);
    }

    public static TrackInfo unknown(String source, String id) {
        return new TrackInfo("Unknown", "Unknown", "", source, "", id);
    }
}
