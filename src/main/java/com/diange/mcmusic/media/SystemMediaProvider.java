package com.diange.mcmusic.media;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Cross-platform system-media metadata provider.
 *
 * <p>Windows: PowerShell + Windows SMTC (Windows 10 1809+).<br>
 * macOS: nowplaying-cli (preferred — it reads the <b>system Now Playing /
 * Media Remote</b> center, so it works for <i>every</i> source including
 * Apple Music, Spotify, and browsers via the Media Session API), with a
 * per-app AppleScript fallback for users who haven't installed
 * nowplaying-cli.<br>
 * Linux: playerctl/MPRIS.</p>
 *
 * <p><b>Important:</b> every AppleScript is wrapped in
 * {@code if application "X" is running then … tell application "X" …}.
 * This guarantees we never auto-launch an app that isn't already open —
 * we only read metadata from apps the user has explicitly started.</p>
 */
public final class SystemMediaProvider {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcmusic-system-media");
        t.setDaemon(true);
        return t;
    });

    public void pollAsync(Consumer<TrackInfo> callback) {
        executor.submit(() -> callback.accept(poll()));
    }

    /** Whether the OS backend can enumerate <i>multiple</i> simultaneously
     *  playing sources. Windows (SMTC GetSessions) and Linux (MPRIS
     *  playerctl -a) can; macOS Now Playing only exposes a single current
     *  track without private MediaRemote access. */
    public boolean supportsMultiSource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("linux")
                || os.contains("freebsd") || os.contains("unix");
    }

    /** Polls all actively-playing sources (multi-source). */
    public void pollAllAsync(Consumer<List<TrackInfo>> callback) {
        executor.submit(() -> callback.accept(pollAll()));
    }

    /** Returns the list of all actively-playing tracks. On macOS this is
     *  equivalent to a single-element (or empty) list. */
    public List<TrackInfo> pollAll() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return windowsAll();
            if (os.contains("mac") || os.contains("darwin")) {
                TrackInfo single = mac();
                return single == null ? Collections.emptyList() : List.of(single);
            }
            if (os.contains("linux") || os.contains("freebsd") || os.contains("unix")) return linuxAll();
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    public TrackInfo poll() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return windows();
            if (os.contains("mac") || os.contains("darwin")) return mac();
            if (os.contains("linux") || os.contains("freebsd") || os.contains("unix")) return linux();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Returns {@code true} if the current OS has a system-media backend. */
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("mac") || os.contains("darwin")
                || os.contains("linux") || os.contains("freebsd") || os.contains("unix");
    }

    public String backendDescription() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "Windows SMTC / PowerShell";
        if (os.contains("mac") || os.contains("darwin")) return "macOS 播放中心 / nowplaying-cli";
        if (os.contains("linux")) return "Linux MPRIS / playerctl";
        return "Unsupported OS";
    }

    /**
     * Returns a human-readable debug string describing the last poll result.
     */
    public String debugInfo() {
        if (!isSupported()) {
            return "Unsupported OS: " + System.getProperty("os.name", "unknown");
        }
        TrackInfo track = null;
        String error = null;
        try {
            track = poll();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (track != null) {
            return "Backend: " + backendDescription() + "\n"
                    + "Title:   " + track.title() + "\n"
                    + "Artist:  " + track.artist() + "\n"
                    + "Album:   " + track.album() + "\n"
                    + "Source:  " + track.source() + "\n"
                    + "Playing: " + track.playing() + "\n"
                    + "Pos:     " + track.positionMs() + "ms\n"
                    + "Dur:     " + track.durationMs() + "ms\n"
                    + "ID:      " + track.stableId();
        }
        if (error != null) {
            return "Backend: " + backendDescription() + "\nError: " + error;
        }
        return "Backend: " + backendDescription() + "\nNo media currently playing.";
    }

    /** Returns the list of installed macOS media app names. */
    public List<String> getMacAppNames() {
        List<String> names = new ArrayList<>();
        for (MacAppRegistry.AppEntry app : MacAppRegistry.getInstalledApps()) {
            names.add(app.name);
        }
        return names;
    }

    /**
     * Set the allow-list of macOS apps to query. Empty list = all apps
     * allowed. Non-empty = only the listed apps are queried; others are
     * skipped entirely (not even checked for running).
     */
    private List<String> allowedMacApps = new ArrayList<>();

    public void setAllowedMacApps(List<String> apps) {
        this.allowedMacApps = apps == null ? new ArrayList<>() : new ArrayList<>(apps);
    }

    private TrackInfo mac() {
        // Preferred: nowplaying-cli reads the macOS system Now Playing center
        // (MediaRemote), so it covers Music, Spotify, browsers (via Media
        // Session API), etc. Only requires `brew install nowplaying-cli`.
        try {
            String out = run("nowplaying-cli", "get", "title", "artist", "album",
                    "playbackRate", "duration", "elapsedTimestamp");
            if (out != null && !out.isBlank()) {
                String[] p = out.trim().split("\\R", -1);
                if (p.length >= 4) {
                    boolean playing = isPlayingRate(p[3]);
                    long duration = p.length > 4 ? parseMs(p[4]) : 0L;
                    long elapsed = p.length > 5 ? parseElapsedTimestamp(p[5]) : 0L;
                    if (playing) {
                        String title = empty(p[0]);
                        String artist = empty(p[1]);
                        String album = empty(p[2]);
                        return new TrackInfo(title, artist, album, "System", "",
                                "mac:" + title + "|" + artist + "|" + album,
                                true, elapsed, duration);
                    }
                    // Paused — return a paused track so the caller can detect
                    // resume transitions.
                    String title = empty(p[0]);
                    String artist = empty(p[1]);
                    String album = empty(p[2]);
                    return new TrackInfo(title, artist, album, "System", "",
                            "mac:" + title + "|" + artist + "|" + album,
                            false, elapsed, duration);
                }
            }
        } catch (Exception ignored) {
            // nowplaying-cli not installed (or failed) — fall through to AppleScript.
        }

        // Per-app AppleScript fallbacks, wrapped in `is running` checks so we
        // never auto-launch an app. Return the first actively-playing app.
        // Filter by the user's allow-list if one is set.
        for (MacAppRegistry.AppEntry app : MacAppRegistry.getInstalledApps()) {
            if (!allowedMacApps.isEmpty() && !allowedMacApps.contains(app.name)) continue;
            try {
                TrackInfo t = queryMacApp(app.name, app.scriptBody);
                if (t != null) return t;
            } catch (Exception ignored) {
                // skip and try the next app
            }
        }
        return null;
    }

    /**
     * Query a single macOS media app via AppleScript. The {@code System Events}
     * process-existence check is the only reliable guard against
     * AppleScript's side effect of auto-launching the app — even
     * {@code if application "X" is running} can trigger Launch Services
     * (observed with VLC on certain macOS configurations). Reading the
     * process list via {@code System Events} never starts the app.
     */
    private TrackInfo queryMacApp(String appName, String scriptBody) {
        try {
            // 1. Confirm the app is actually running (process exists) without
            //    ever invoking `tell application "X"` (which would launch it).
            // 2. Only after the process is confirmed do we `tell` it.
            String script = "tell application \"System Events\"\n"
                    + "  if not (exists process \"" + appName + "\") then return \"\"\n"
                    + "end tell\n"
                    + "tell application \"" + appName + "\"\n"
                    + scriptBody + "\n"
                    + "  end tell\n"
                    + "return \"\"";
            String out = run("osascript", "-e", script);
            if (out == null || out.isBlank()) return null;
            String[] p = out.trim().split("\\R", -1);
            if (p.length < 3) return null;
            // AppleScript queries don't provide position/duration — leave as 0.
            TrackInfo t = new TrackInfo(empty(p[0]), empty(p[1]), empty(p[2]), "System", "",
                    "mac-" + appName.toLowerCase(Locale.ROOT) + ":" + p[0] + "|" + p[1] + "|" + p[2]);
            // Mark the app as authorized since the query succeeded.
            MacAppRegistry.markAuthorized(appName);
            return t;
        } catch (Exception e) {
            // If the error looks like a permission denial, mark it.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("-1743") || msg.contains("not authorized")) {
                MacAppRegistry.markDenied(appName);
            }
            return null;
        }
    }

    private TrackInfo linux() throws Exception {
        String out = run("playerctl", "metadata", "--format",
                "{{title}}\\n{{artist}}\\n{{album}}\\n{{status}}\\n{{mpris:trackid}}\\n{{mpris:artUrl}}\\n{{position}}\\n{{mpris:length}}");
        if (out == null || out.isBlank()) return null;
        String[] p = out.trim().split("\\R", -1);
        if (p.length < 4) return null;
        boolean playing = p[3].equalsIgnoreCase("Playing");
        String id = p.length > 4 ? p[4] : p[0];
        String art = p.length > 5 ? p[5] : "";
        long pos = p.length > 6 ? parseMs(p[6]) : 0L;
        long dur = p.length > 7 ? parseMs(p[7]) : 0L;
        return new TrackInfo(empty(p[0]), empty(p[1]), empty(p[2]), "System", art, "linux:" + id,
                playing, pos, dur);
    }

    private TrackInfo windows() throws Exception {
        String script = """
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
                $m=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync().GetAwaiter().GetResult()
                $s=$m.GetCurrentSession(); if($null -eq $s){exit 0}
                $p=$s.TryGetMediaPropertiesAsync().GetAwaiter().GetResult()
                $play=$s.GetPlaybackInfo().PlaybackStatus.ToString()
                if($play -ne 'Playing'){exit 0}
                Write-Output $p.Title; Write-Output $p.Artist; Write-Output $p.AlbumTitle
                """;
        String out = run("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        if (out == null || out.isBlank()) return null;
        String[] p = out.trim().split("\\R", -1);
        if (p.length < 3) return null;
        return make(p[0], p[1], p[2], "System", "win:" + p[0] + "|" + p[1] + "|" + p[2]);
    }

    /** Windows multi-source: enumerate every SMTC session that is Playing. */
    private List<TrackInfo> windowsAll() throws Exception {
        String script = """
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
                $m=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync().GetAwaiter().GetResult()
                $sessions=$m.GetSessions()
                foreach($s in $sessions){
                    $play=$s.GetPlaybackInfo().PlaybackStatus.ToString()
                    if($play -eq 'Playing'){
                        $p=$s.TryGetMediaPropertiesAsync().GetAwaiter().GetResult()
                        Write-Output $p.Title; Write-Output $p.Artist; Write-Output $p.AlbumTitle
                        Write-Output '---MCMUSIC---'
                    }
                }
                """;
        String out = run("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        if (out == null || out.isBlank()) return Collections.emptyList();
        return parseDelimited(out, "---MCMUSIC---", "win");
    }

    /** Linux multi-source: enumerate every MPRIS player that is Playing. */
    private List<TrackInfo> linuxAll() throws Exception {
        String out = run("playerctl", "-a", "metadata", "--format",
                "{{title}}\\n{{artist}}\\n{{album}}\\n{{status}}\\n---MCMUSIC---");
        if (out == null || out.isBlank()) return Collections.emptyList();
        // Each block is: title, artist, album, status, then the delimiter.
        // We drop the status line and only keep Playing blocks.
        List<TrackInfo> result = new ArrayList<>();
        String[] blocks = out.split("---MCMUSIC---");
        for (String block : blocks) {
            String[] p = block.trim().split("\\R", -1);
            if (p.length < 4) continue;
            if (!p[3].equalsIgnoreCase("Playing")) continue;
            result.add(new TrackInfo(empty(p[0]), empty(p[1]), empty(p[2]), "System", "",
                    "linux:" + (p[0] + "|" + p[1] + "|" + p[2])));
        }
        return result;
    }

    /** Parse blocks delimited by {@code delimiter}; each block is 3 lines
     *  (title, artist, album). */
    private static List<TrackInfo> parseDelimited(String out, String delimiter, String platform) {
        List<TrackInfo> result = new ArrayList<>();
        String[] blocks = out.split(delimiter);
        for (String block : blocks) {
            String[] p = block.trim().split("\\R", -1);
            if (p.length < 3) continue;
            result.add(make(p[0], p[1], p[2], "System",
                    platform + ":" + p[0] + "|" + p[1] + "|" + p[2]));
        }
        return result;
    }

    private static boolean isPlayingRate(String rate) {
        try { return Double.parseDouble(rate.trim()) > 0.0; }
        catch (NumberFormatException e) { return false; }
    }

    /** Parse a value that may be in seconds, milliseconds, or microseconds. */
    private static long parseMs(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            double v = Double.parseDouble(s.trim());
            // playerctl returns microseconds, nowplaying-cli returns seconds
            if (v > 1_000_000) return (long) (v / 1_000); // microseconds → ms
            if (v > 100_000) return (long) v; // already ms
            return (long) (v * 1000); // seconds → ms
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Parse nowplaying-cli elapsedTimestamp (epoch seconds) into ms. */
    private static long parseElapsedTimestamp(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            double epoch = Double.parseDouble(s.trim());
            if (epoch < 1_000_000_000L) return 0L; // not a timestamp
            long now = System.currentTimeMillis();
            long elapsed = now - (long) (epoch * 1000);
            return Math.max(0, elapsed);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static TrackInfo make(String title, String artist, String album, String source, String id) {
        return new TrackInfo(empty(title), empty(artist), empty(album), source, "", id);
    }

    private static String empty(String s) { return s == null || s.isBlank() ? "Unknown" : s.trim(); }

    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        p.waitFor();
        return p.exitValue() == 0 ? b.toString() : null;
    }
}
