package com.diange.mcmusic.media;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of known macOS media-playing apps.
 *
 * <p>Each entry has:</p>
 * <ul>
 *   <li>A display name (matching the AppleScript application name).</li>
 *   <li>A category: {@link Category#MUSIC}, {@link Category#BROWSER}, or
 *       {@link Category#PLAYER}.</li>
 *   <li>An AppleScript body that returns {@code title\nartist\nalbum} or "".</li>
 *   <li>The on-disk bundle path used for installation detection.</li>
 * </ul>
 *
 * <p>Installation is detected by checking whether the {@code .app} bundle
 * exists in {@code /Applications} or {@code ~/Applications}.</p>
 *
 * <p>Automation permission is checked by sending a trivial no-op
 * AppleScript to the app <b>only when it is running</b> (to avoid
 * auto-launching it). The result is cached in memory.</p>
 */
public final class MacAppRegistry {

    public enum Category { MUSIC, BROWSER, PLAYER }

    /** Authorization state for AppleScript automation. */
    public enum AuthState { UNKNOWN, AUTHORIZED, DENIED }

    public static final class AppEntry {
        public final String name;
        public final Category category;
        public final String scriptBody;
        public final String bundlePath;

        public AppEntry(String name, Category category, String scriptBody, String bundlePath) {
            this.name = name;
            this.category = category;
            this.scriptBody = scriptBody;
            this.bundlePath = bundlePath;
        }
    }

    // ---- Curated app list ----

    private static final List<AppEntry> ALL_APPS = List.of(
            // --- Music software ---
            new AppEntry("Music", Category.MUSIC,
                    "if player state is not playing then return \"\"\n"
                  + "try\n"
                  + "  set t to name of current track\n"
                  + "  set a to artist of current track\n"
                  + "  set al to album of current track\n"
                  + "  return t & \"\\n\" & a & \"\\n\" & al\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Music.app"),
            new AppEntry("Spotify", Category.MUSIC,
                    "if player state is not playing then return \"\"\n"
                  + "try\n"
                  + "  set t to name of current track\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try\n"
                  + "try\n"
                  + "  set a to artist of current track\n"
                  + "on error\n"
                  + "  set a to \"Unknown\"\n"
                  + "end try\n"
                  + "try\n"
                  + "  set al to album of current track\n"
                  + "on error\n"
                  + "  set al to \"\"\n"
                  + "end try\n"
                  + "return t & \"\\n\" & a & \"\\n\" & al",
                    "/Applications/Spotify.app"),
            new AppEntry("QQ Music", Category.MUSIC,
                    "if player state is not playing then return \"\"\n"
                  + "try\n"
                  + "  set t to name of current track\n"
                  + "  set a to artist of current track\n"
                  + "  set al to album of current track\n"
                  + "  return t & \"\\n\" & a & \"\\n\" & al\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/QQMusic.app"),
            new AppEntry("VOX", Category.MUSIC,
                    "if player state is not playing then return \"\"\n"
                  + "try\n"
                  + "  set t to name of current track\n"
                  + "  set a to artist of current track\n"
                  + "  set al to album of current track\n"
                  + "  return t & \"\\n\" & a & \"\\n\" & al\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/VOX.app"),

            // --- Browsers ---
            new AppEntry("Google Chrome", Category.BROWSER,
                    "if (count of windows) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set jsResult to execute active tab of front window javascript \"var m = navigator.mediaSession && navigator.mediaSession.metadata; m ? (m.title || '') + '\\\\n' + (m.artist || '') + '\\\\n' + (m.album || '') : ''\"\n"
                  + "  if jsResult is \"\" then return \"\"\n"
                  + "  return jsResult\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Google Chrome.app"),
            new AppEntry("Microsoft Edge", Category.BROWSER,
                    "if (count of windows) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set jsResult to execute active tab of front window javascript \"var m = navigator.mediaSession && navigator.mediaSession.metadata; m ? (m.title || '') + '\\\\n' + (m.artist || '') + '\\\\n' + (m.album || '') : ''\"\n"
                  + "  if jsResult is \"\" then return \"\"\n"
                  + "  return jsResult\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Microsoft Edge.app"),
            new AppEntry("Firefox", Category.BROWSER,
                    "if (count of windows) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set jsResult to execute active tab of front window javascript \"var m = navigator.mediaSession && navigator.mediaSession.metadata; m ? (m.title || '') + '\\\\n' + (m.artist || '') + '\\\\n' + (m.album || '') : ''\"\n"
                  + "  if jsResult is \"\" then return \"\"\n"
                  + "  return jsResult\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Firefox.app"),
            new AppEntry("Arc", Category.BROWSER,
                    "if (count of windows) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set jsResult to execute active tab of front window javascript \"var m = navigator.mediaSession && navigator.mediaSession.metadata; m ? (m.title || '') + '\\\\n' + (m.artist || '') + '\\\\n' + (m.album || '') : ''\"\n"
                  + "  if jsResult is \"\" then return \"\"\n"
                  + "  return jsResult\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Arc.app"),
            new AppEntry("Safari", Category.BROWSER,
                    "if (count of windows) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set t to name of front document\n"
                  + "  return t & \"\\n\" & \"Unknown\" & \"\\n\" & \"\"\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/Safari.app"),

            // --- Players ---
            new AppEntry("VLC", Category.PLAYER,
                    "if not playing then return \"\"\n"
                  + "try\n"
                  + "  set ti to name of current item\n"
                  + "  set ar to artist of current item\n"
                  + "  try\n"
                  + "    set al to album of current item\n"
                  + "  on error\n"
                  + "    set al to \"\"\n"
                  + "  end try\n"
                  + "  return ti & \"\\n\" & ar & \"\\n\" & al\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/VLC.app"),
            new AppEntry("QuickTime Player", Category.PLAYER,
                    "if (count of documents) is 0 then return \"\"\n"
                  + "try\n"
                  + "  set t to name of document 1\n"
                  + "  return t & \"\\n\" & \"Unknown\" & \"\\n\" & \"\"\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/QuickTime Player.app"),
            new AppEntry("IINA", Category.PLAYER,
                    "if not playing then return \"\"\n"
                  + "try\n"
                  + "  set t to name of current track\n"
                  + "  set a to artist of current track\n"
                  + "  set al to album of current track\n"
                  + "  return t & \"\\n\" & a & \"\\n\" & al\n"
                  + "on error\n"
                  + "  return \"\"\n"
                  + "end try",
                    "/Applications/IINA.app")
    );

    // ---- Installation detection ----

    /** Returns only the apps whose {@code .app} bundle exists on disk. */
    public static List<AppEntry> getInstalledApps() {
        List<AppEntry> installed = new ArrayList<>();
        for (AppEntry app : ALL_APPS) {
            if (isInstalled(app)) {
                installed.add(app);
            }
        }
        return installed;
    }

    /**
     * Whether the app's {@code .app} bundle exists. Checks all the standard
     * macOS locations — {@code /System/Applications} (built-in apps like
     * Music, Safari, QuickTime Player), {@code /Applications}, and
     * {@code ~/Applications} — so Apple Music and other system apps are
     * correctly detected.
     */
    public static boolean isInstalled(AppEntry app) {
        if (app == null || app.bundlePath == null || app.bundlePath.isBlank()) return false;
        String appName = app.bundlePath.substring(app.bundlePath.lastIndexOf('/') + 1);
        String home = System.getProperty("user.home", "");
        String[] roots = {
                "/System/Applications/",
                "/Applications/",
                home + "/Applications/"
        };
        for (String root : roots) {
            if (Files.isDirectory(Path.of(root + appName))) return true;
        }
        return false;
    }

    /** Returns the {@link AppEntry} for the given app name, or null. */
    public static AppEntry find(String appName) {
        if (appName == null) return null;
        for (AppEntry app : ALL_APPS) {
            if (app.name.equals(appName)) return app;
        }
        return null;
    }

    /** Returns all known app names (regardless of installation). */
    public static List<String> getAllAppNames() {
        List<String> names = new ArrayList<>();
        for (AppEntry app : ALL_APPS) names.add(app.name);
        return names;
    }

    /** Returns all known app entries (regardless of installation). */
    public static List<AppEntry> getAllApps() {
        return new ArrayList<>(ALL_APPS);
    }

    // ---- Permission state caching ----

    /** In-memory cache of authorization states, keyed by app name. */
    private static final Map<String, AuthState> authCache = new ConcurrentHashMap<>();

    /**
     * Returns the cached authorization state for an app. Does NOT perform a
     * live check — use {@link #refreshAuthState(String)} for that.
     */
    public static AuthState getAuthState(String appName) {
        return authCache.getOrDefault(appName, AuthState.UNKNOWN);
    }

    /**
     * Marks an app as successfully queried (AUTHORIZED). Called by
     * {@link SystemMediaProvider} whenever an AppleScript query returns data.
     */
    public static void markAuthorized(String appName) {
        if (appName != null) authCache.put(appName, AuthState.AUTHORIZED);
    }

    /**
     * Marks an app as denied (error -1743 or similar). Called by
     * {@link SystemMediaProvider} when a query fails due to permissions.
     */
    public static void markDenied(String appName) {
        if (appName != null) authCache.put(appName, AuthState.DENIED);
    }

    /**
     * Performs a live authorization check for the given app by sending a
     * trivial no-op AppleScript. <b>Only checks when the app is running</b>
     * to avoid auto-launching it. Updates the in-memory cache.
     *
     * @return the resolved {@link AuthState}
     */
    public static AuthState refreshAuthState(String appName) {
        AppEntry entry = find(appName);
        if (entry == null) return AuthState.UNKNOWN;

        // Check if the app process is running via System Events (safe —
        // never launches the app).
        if (!isProcessRunning(appName)) {
            return authCache.getOrDefault(appName, AuthState.UNKNOWN);
        }

        return probeAuth(appName);
    }

    /**
     * Actively requests automation permission for an app by sending a no-op
     * tell. This is called when the user clicks a "needs permission" button
     * in the settings screen — it triggers the macOS automation permission
     * dialog (or returns the result directly if already granted).
     *
     * <p>Unlike {@link #refreshAuthState}, this does <b>not</b> require the
     * app to already be running, because the user explicitly asked for it.</p>
     *
     * @return the resolved {@link AuthState}
     */
    public static AuthState requestAuthorization(String appName) {
        AppEntry entry = find(appName);
        if (entry == null) return AuthState.UNKNOWN;
        return probeAuth(appName);
    }

    /**
     * Sends {@code tell application "X" to return "ok"} and maps the result
     * to an {@link AuthState}. The key insight: a permission failure always
     * surfaces as error -1743; any other outcome (exit 0, or a non-1743
     * error) means the tell reached the app, so the app is authorized —
     * which fixes apps that were already granted but still showed
     * "needs permission".
     */
    private static AuthState probeAuth(String appName) {
        try {
            String script = "tell application \"" + appName + "\" to return \"ok\"";
            Process p = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            String result = out.toString().trim();

            // Exit 0 = osascript executed the tell successfully = authorized
            // (regardless of whether the app echoed back a literal "ok").
            if (p.exitValue() == 0 || "ok".equals(result)) {
                authCache.put(appName, AuthState.AUTHORIZED);
                return AuthState.AUTHORIZED;
            }
            // The only reliable "not authorized" signal is error -1743.
            if (result.contains("-1743") || result.contains("not authorized")
                    || result.contains("Not authorized")) {
                authCache.put(appName, AuthState.DENIED);
                return AuthState.DENIED;
            }
            // Any other error is not a permission error — leave unknown
            // rather than wrongly marking it denied.
            return authCache.getOrDefault(appName, AuthState.UNKNOWN);
        } catch (Exception e) {
            return authCache.getOrDefault(appName, AuthState.UNKNOWN);
        }
    }

    /**
     * Checks whether a macOS app process is running (via System Events).
     * Tries the exact app name first, then the name with spaces removed —
     * some apps' process names differ from their display name (e.g. "QQ Music"
     * runs as "QQMusic"), which previously caused a false "not running".
     */
    private static boolean isProcessRunning(String appName) {
        try {
            String compact = appName.replace(" ", "");
            String script = "tell application \"System Events\" to return (exists process whose "
                    + "name is \"" + appName + "\" or name is \"" + compact + "\")";
            Process p = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return "true".equalsIgnoreCase(out.toString().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** Clears the auth cache (e.g. when the user re-opens the settings screen). */
    public static void clearAuthCache() {
        authCache.clear();
    }
}
