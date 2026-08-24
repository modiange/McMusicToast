package com.diange.mcmusic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class McMusicConfig {
    public enum SourceMode { MINECRAFT, SYSTEM, BOTH }

    /**
     * 控制原版游戏音乐弹窗（NowPlayingToast）的显示方式，映射到
     * {@link MusicToastDisplayState}。注意：为了让系统媒体弹窗在 HUD 占第一行，
     * 这个选项在运行时被 {@code McMusicClient#updateVanillaMusicToastOption} 动态改写：
     * <ul>
     *   <li>{@link #PAUSE_MENU} → 游戏弹窗仅暂停菜单显示（HUD 永不显示）。</li>
     *   <li>{@link #PAUSE_MENU_AND_TOAST} → 平时同 PAUSE_MENU，仅在游戏音乐切换后的
     *       几秒窗口内临时切为 HUD 显示（让原版"换歌提示"短暂露脸）。</li>
     * </ul>
     */
    public enum MusicToastDisplay { PAUSE_MENU, PAUSE_MENU_AND_TOAST }

    /**
     * When to show the system-media toast. Three mutually exclusive modes:
     * <ul>
     *   <li>{@link #ON_CHANGE} — show whenever the track info changes (default).</li>
     *   <li>{@link #ON_RESUME} — show only when playback resumes after a pause.</li>
     *   <li>{@link #ON_TRACK_END} — show when the previous track counts down
     *       its last 3 seconds and transitions to the next track.</li>
     * </ul>
     */
    public enum SystemDisplayMode { ON_CHANGE, ON_RESUME, ON_TRACK_END }

    /**
     * Where the <i>system-media</i> toast is shown. Two mutually exclusive
     * choices:
     * <ul>
     *   <li>{@link #HUD_ONLY} — only the in-game HUD toast (transient, fades
     *       after {@link #displayDurationMs}); nothing pinned in the pause
     *       menu.</li>
     *   <li>{@link #HUD_AND_PAUSE_MENU} — in-game HUD toast <i>plus</i> a
     *       persistent toast in the pause menu (default).</li>
     * </ul>
     */
    public enum SystemToastLocation { HUD_ONLY, HUD_AND_PAUSE_MENU }

    public boolean enabled = true;
    public SourceMode sourceMode = SourceMode.BOTH;

    /** Controls the vanilla in-game music toast display mode. */
    public MusicToastDisplay musicToastDisplay = MusicToastDisplay.PAUSE_MENU_AND_TOAST;

    /** Where the system-media toast is shown. */
    public SystemToastLocation systemToastLocation = SystemToastLocation.HUD_AND_PAUSE_MENU;

    /** When to show the system-media toast. */
    public SystemDisplayMode systemDisplayMode = SystemDisplayMode.ON_CHANGE;
    public int displayDurationMs = 5000;
    public boolean playToastSound = true;

    /** Polling interval for external media (ms). Lower = more responsive. */
    public int systemPollMs = 150;

    /** Max number of recent system tracks pinned in the pause menu. */
    public int pauseMaxTracks = 5;

    /**
     * Enumerate <i>multiple</i> simultaneously-playing system sources.
     * Only meaningful on Windows (SMTC GetSessions) and Linux (MPRIS
     * playerctl -a). macOS Now Playing exposes a single current track
     * without private MediaRemote access, so this is forced off there.
     */
    public boolean multiSourceEnabled = false;

    /**
     * On macOS, which media apps the mod is allowed to read from. Empty =
     * all apps allowed (default). App names match the AppleScript
     * application name (e.g. "Music", "Spotify", "VLC", "Google Chrome",
     * "Arc", "Safari", "VOX").
     */
    public List<String> allowedMacApps = new ArrayList<>();

    public List<TrackRule> minecraftTracks = new ArrayList<>();

    public static final class TrackRule {
        public String id = "minecraft:music.overworld";
        public String title = "";
        public String artist = "";
        public String album = "";
        public String cover = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcmusic.json");
    private static McMusicConfig instance;

    public static McMusicConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) { instance = GSON.fromJson(reader, McMusicConfig.class); }
            } else instance = new McMusicConfig();
        } catch (Exception e) {
            instance = new McMusicConfig();
        }
        if (instance == null) instance = new McMusicConfig();
        // Migrate legacy fields: legacy "ON_TRACK_END" enum value is removed,
        // so any old config defaulting to it falls back to ON_CHANGE.
        try {
            if (instance.systemDisplayMode == null) instance.systemDisplayMode = SystemDisplayMode.ON_CHANGE;
        } catch (Throwable ignored) {
            instance.systemDisplayMode = SystemDisplayMode.ON_CHANGE;
        }
        try {
            if (instance.musicToastDisplay == null) instance.musicToastDisplay = MusicToastDisplay.PAUSE_MENU_AND_TOAST;
        } catch (Throwable ignored) {
            instance.musicToastDisplay = MusicToastDisplay.PAUSE_MENU_AND_TOAST;
        }
        try {
            if (instance.systemToastLocation == null) instance.systemToastLocation = SystemToastLocation.HUD_AND_PAUSE_MENU;
        } catch (Throwable ignored) {
            instance.systemToastLocation = SystemToastLocation.HUD_AND_PAUSE_MENU;
        }
        if (instance.systemPollMs < 100) instance.systemPollMs = 100;
        if (instance.pauseMaxTracks < 1) instance.pauseMaxTracks = 1;
        if (instance.pauseMaxTracks > 5) instance.pauseMaxTracks = 5;
        instance.save();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) { GSON.toJson(this, writer); }
        } catch (Exception ignored) {}
    }
}
