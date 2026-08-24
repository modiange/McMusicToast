package com.diange.mcmusic.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Tracks the active system-media track(s).
 *
 * <p>The active set is <b>wholly replaced</b> by each poll result, so tracks
 * that disappear from the source are dropped immediately (the user's
 * "clear invalid entries" rule). Whether the poll yields one track (macOS
 * single-source) or several (Windows SMTC / Linux MPRIS multi-source) is up
 * to the caller — this tracker just mirrors what it's handed:</p>
 * <ul>
 *   <li>Empty / null list → clear everything.</li>
 *   <li>Non-empty list → dedup by {@link TrackInfo#stableId()} and trim to
 *       {@code maxActive} entries.</li>
 * </ul>
 *
 * <p>Singleton accessed via {@link #get()}. All mutating methods are
 * synchronized (the system poll runs on a background thread).</p>
 */
public final class RecentTracksTracker {
    private static final RecentTracksTracker INSTANCE = new RecentTracksTracker();

    private final LinkedHashMap<String, TrackInfo> tracks = new LinkedHashMap<>();

    private RecentTracksTracker() {}

    public static RecentTracksTracker get() { return INSTANCE; }

    /**
     * Replace the active set with the latest poll result.
     *
     * @param latest    latest poll result (list of tracks), or null/empty when
     *                  nothing is playing
     * @param maxActive maximum number of tracks to keep
     * @return ordered active list, never null
     */
    public synchronized List<TrackInfo> record(List<TrackInfo> latest, int maxActive) {
        tracks.clear();
        if (latest == null || latest.isEmpty()) {
            return Collections.emptyList();
        }
        int cap = Math.max(1, maxActive);
        for (TrackInfo t : latest) {
            if (t == null || t.stableId() == null || t.stableId().isBlank()) continue;
            tracks.put(t.stableId(), t);
            if (tracks.size() >= cap) break;
        }
        return new ArrayList<>(tracks.values());
    }

    /** Returns a snapshot of the current track list (in insertion order). */
    public synchronized List<TrackInfo> snapshot() {
        return new ArrayList<>(tracks.values());
    }

    public synchronized void clear() {
        tracks.clear();
    }
}
