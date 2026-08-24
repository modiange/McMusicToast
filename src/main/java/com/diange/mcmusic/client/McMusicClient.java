package com.diange.mcmusic.client;

import com.diange.mcmusic.config.McMusicConfig;
import com.diange.mcmusic.media.RecentTracksTracker;
import com.diange.mcmusic.media.SystemMediaProvider;
import com.diange.mcmusic.media.TrackInfo;
import com.diange.mcmusic.toast.CustomMusicToast;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MusicToastDisplayState;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class McMusicClient implements ClientModInitializer {
    private static McMusicConfig config;
    private static final SystemMediaProvider SYSTEM = new SystemMediaProvider();
    /** Latest multi-source poll result (list of tracks). */
    private static final AtomicReference<List<TrackInfo>> latestSystem = new AtomicReference<>(List.of());
    private static long lastSystemPoll;
    private static KeyMapping configKey;
    private static boolean vanillaOptionSynced = false;

    /** Last observed in-game music translation key (to detect music switches). */
    private static String lastGameMusicKey = null;
    /** Timestamp of the last in-game music switch (drives the game-toast window). */
    private static long lastGameMusicSwitchMs = 0;
    /** Currently applied vanilla musicToast state (avoids redundant .set() calls). */
    private static MusicToastDisplayState appliedMusicToast = null;

    /** Slot tokens currently in use by the mod (e.g. "mcmusic-slot-0"). */
    private static final Set<String> activeSlots = new HashSet<>();

    // ---- Display-trigger state (for ON_RESUME and ON_TRACK_END modes) ----

    /** Previous poll's playback state, used to detect pause → resume. */
    private static boolean prevPlaying = false;
    /** Previous poll's track stableId (to detect track changes). */
    private static String prevTrackId = null;
    /** Previous poll's remaining time (ms), used for ON_TRACK_END countdown. */
    private static long prevRemainingMs = Long.MAX_VALUE;
    /** Whether the previous track was in its last 3 seconds (countdown). */
    private static boolean prevInCountdown = false;
    /** The toast currently shown by a non-ON_CHANGE trigger (resume/track-end),
     *  or null. In ON_CHANGE mode this is unused (toasts are managed by the
     *  normal sync path). */
    private static String triggeredToastSlot = null;

    @Override
    public void onInitializeClient() {
        config = McMusicConfig.get();
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mcmusic.open_config", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (configKey.consumeClick()) {
                client.gui.setScreen(new McMusicConfigScreen(client.gui.screen()));
            }
            if (!vanillaOptionSynced) {
                vanillaOptionSynced = true;
                SYSTEM.setAllowedMacApps(config.allowedMacApps);
            }
            tick(client);
        });
    }

    /** True when the mod should enable the vanilla in-game music toast. */
    public static boolean shouldUseVanillaMusicToast() {
        return config != null && config.enabled &&
                (config.sourceMode == McMusicConfig.SourceMode.MINECRAFT ||
                 config.sourceMode == McMusicConfig.SourceMode.BOTH);
    }

    /**
     * 动态控制原版 {@code musicToast} 选项——这是"系统弹窗在 HUD 占第一行"的关键。
     *
     * <p><b>为什么这么做</b>：原版游戏音乐弹窗和我们的系统弹窗共享左上角第一行。
     * 若游戏弹窗常驻 HUD（PAUSE_AND_TOAST），系统弹窗会被挤到第二行。系统媒体才是
     * 用户关心的主角，所以让游戏弹窗默认只在暂停菜单显示（PAUSE），只在游戏音乐
     * 切换后的几秒窗口内临时切回 PAUSE_AND_TOAST，让原版"换歌提示"短暂露个脸。</p>
     *
     * <p>规则（每 tick 调用）：</p>
     * <ul>
     *   <li>原版音乐弹窗被禁用（sourceMode=SYSTEM）→ {@code NEVER}。</li>
     *   <li>{@code musicToastDisplay == PAUSE_MENU} → {@code PAUSE}（游戏弹窗仅暂停菜单）。</li>
     *   <li>{@code musicToastDisplay == PAUSE_MENU_AND_TOAST} → 平时 {@code PAUSE}，
     *       仅在音乐切换窗口内 {@code PAUSE_AND_TOAST}。</li>
     * </ul>
     */
    public static void updateVanillaMusicToastOption() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        MusicToastDisplayState state;
        if (!shouldUseVanillaMusicToast()) {
            state = MusicToastDisplayState.NEVER;
        } else if (config.musicToastDisplay == McMusicConfig.MusicToastDisplay.PAUSE_MENU) {
            state = MusicToastDisplayState.PAUSE;
        } else {
            state = isInGameMusicSwitchWindow(mc)
                    ? MusicToastDisplayState.PAUSE_AND_TOAST
                    : MusicToastDisplayState.PAUSE;
        }
        // Only apply on change — set() recreates the vanilla NowPlayingToast.
        if (state != appliedMusicToast) {
            appliedMusicToast = state;
            mc.options.musicToast().set(state);
        }
    }

    /**
     * Detects when the in-game music changes and returns whether we're inside
     * the "game toast visible" window (a few seconds after the switch). Side
     * effect: records the switch timestamp / last key.
     */
    private static boolean isInGameMusicSwitchWindow(Minecraft mc) {
        String key;
        try {
            key = mc.getMusicManager().getCurrentMusicTranslationKey();
        } catch (Throwable ignored) {
            key = null;
        }
        boolean switched = key != null && !key.equals(lastGameMusicKey);
        if (switched) {
            lastGameMusicKey = key;
            lastGameMusicSwitchMs = System.currentTimeMillis();
        }
        // Window: 5s display + ~600ms slide-out.
        return System.currentTimeMillis() - lastGameMusicSwitchMs < 5600L;
    }

    private static void tick(Minecraft client) {
        // Dynamically control the vanilla music toast (HUD first-row logic).
        // Called even when disabled so it falls back to NEVER.
        updateVanillaMusicToastOption();

        if (!config.enabled) return;

        if (config.sourceMode == McMusicConfig.SourceMode.SYSTEM ||
            config.sourceMode == McMusicConfig.SourceMode.BOTH) {
            long now = System.currentTimeMillis();
            if (now - lastSystemPoll >= Math.max(100, config.systemPollMs)) {
                lastSystemPoll = now;
                boolean multi = config.multiSourceEnabled && SYSTEM.supportsMultiSource();
                if (multi) {
                    SYSTEM.pollAllAsync(latestSystem::set);
                } else {
                    SYSTEM.pollAsync(t -> latestSystem.set(t == null ? List.of() : List.of(t)));
                }
            }
            List<TrackInfo> current = latestSystem.get();
            handleSystemTracks(client, current);
        } else {
            // Source disabled: clear everything.
            if (!activeSlots.isEmpty()) {
                hideAllSystemToasts(client);
            }
            RecentTracksTracker.get().clear();
            latestSystem.set(List.of());
            resetTriggerState();
        }
    }

    private static void resetTriggerState() {
        prevPlaying = false;
        prevTrackId = null;
        prevRemainingMs = Long.MAX_VALUE;
        prevInCountdown = false;
        triggeredToastSlot = null;
    }

    private static void handleSystemTracks(Minecraft client, List<TrackInfo> current) {
        // Filter out tracks that are duplicates of the in-game music.
        List<TrackInfo> filtered = new ArrayList<>();
        if (current != null) {
            for (TrackInfo t : current) {
                if (t != null && !isDuplicateOfInGameMusic(t, client)) {
                    filtered.add(t);
                }
            }
        }

        int max = config == null ? 5 : config.pauseMaxTracks;
        List<TrackInfo> tracks = RecentTracksTracker.get().record(filtered, max);

        // In multi-source mode, always sync (ON_CHANGE behaviour).
        // In single-source mode, use the configured display trigger.
        if (config.multiSourceEnabled && SYSTEM.supportsMultiSource()) {
            syncSystemToasts(client, tracks);
        } else {
            handleSingleSourceDisplayTrigger(client, tracks);
        }
    }

    /**
     * Handles the three display-trigger modes for single-source (macOS):
     * <ul>
     *   <li>ON_CHANGE — show whenever the track changes (default).</li>
     *   <li>ON_RESUME — show when playback resumes after a pause, or when
     *       the track info changes.</li>
     *   <li>ON_TRACK_END — show when the previous track was in its last
     *       3 seconds and a new track starts.</li>
     * </ul>
     */
    private static void handleSingleSourceDisplayTrigger(Minecraft client, List<TrackInfo> tracks) {
        TrackInfo current = tracks.isEmpty() ? null : tracks.get(0);
        String currentId = current == null ? null : current.stableId();
        boolean currentlyPlaying = current != null && current.playing();

        // In the pause menu, toasts are pinned — don't trigger display
        // logic, just keep the current track shown.
        net.minecraft.client.gui.screens.Screen screen = client.gui.screen();
        boolean inPauseMenu = screen instanceof net.minecraft.client.gui.screens.PauseScreen;
        if (inPauseMenu) {
            syncPauseMenuToast(client, current);
            // Still update state for after the pause menu closes.
            prevPlaying = currentlyPlaying;
            prevTrackId = currentId;
            return;
        }

        // Any other screen (标题 / 二级界面): don't run the per-tick HUD
        // trigger. The toast keeps its global timer (see CustomMusicToast.update)
        // so it continues showing / times out normally; we only keep state
        // fresh for when the user returns to the HUD.
        if (screen != null) {
            prevPlaying = currentlyPlaying;
            prevTrackId = currentId;
            return;
        }

        // Compute remaining time for ON_TRACK_END.
        long remainingMs = Long.MAX_VALUE;
        if (current != null && current.durationMs() > 0) {
            remainingMs = current.durationMs() - current.positionMs();
        }
        boolean inCountdown = remainingMs <= 3000L && remainingMs >= 0;

        // First time we ever see a track → always show it (so the currently
        // playing song appears immediately on first launch).
        boolean firstDetection = prevTrackId == null && currentId != null && currentlyPlaying;

        boolean shouldShow = false;

        switch (config.systemDisplayMode) {
            case ON_CHANGE:
                // Show whenever the track ID changes.
                shouldShow = firstDetection || (currentId != null && !currentId.equals(prevTrackId));
                break;

            case ON_RESUME:
                // Show when playback resumes after a pause, OR when the
                // track info changes (重新播放或信息改变).
                shouldShow = firstDetection
                        || (currentlyPlaying && !prevPlaying && currentId != null)
                        || (currentId != null && !currentId.equals(prevTrackId));
                // Hide toast when paused.
                if (!currentlyPlaying && prevPlaying) {
                    hideTriggeredToast(client);
                }
                break;

            case ON_TRACK_END:
                // Show when a new track starts AND the previous track was
                // in its countdown (last 3 seconds). This detects "natural
                // track transition" as opposed to a manual skip. Also show
                // on first detection.
                if (firstDetection) {
                    shouldShow = true;
                } else if (currentId != null && !currentId.equals(prevTrackId)) {
                    if (prevInCountdown) {
                        shouldShow = true;
                    }
                    // Reset countdown state on track change.
                    prevInCountdown = inCountdown;
                }
                // Track countdown state for the current track.
                if (currentId != null && currentId.equals(prevTrackId)) {
                    prevInCountdown = inCountdown;
                }
                break;
        }

        if (shouldShow) {
            showTriggeredToast(client, current);
        } else if (currentId == null) {
            // No track playing — hide any triggered toast.
            hideTriggeredToast(client);
        }

        // Update state for next tick.
        prevPlaying = currentlyPlaying;
        prevTrackId = currentId;
        prevRemainingMs = remainingMs;
    }

    /**
     * Shows (or updates) the triggered toast at slot 0.
     *
     * <p>When the track <b>changes</b>, the toast is re-added rather than
     * updated in-place — this resets the vanilla timer (becameFullyVisibleAt
     * / fullyVisibleFor), giving the new track a full display-duration
     * "delay". When the track is the same (e.g. ON_RESUME), it just re-shows.</p>
     */
    private static void showTriggeredToast(Minecraft client, TrackInfo track) {
        if (client == null || client.gui == null || track == null) return;
        ToastManager tm = client.gui.toastManager();

        String slotToken = "mcmusic-slot-0";
        CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
        if (existing != null) {
            boolean idChanged = existing.getTrack() == null
                    || !existing.getTrack().stableId().equals(track.stableId());
            if (idChanged) {
                // Track changed → re-add to reset the timer (delay) + re-slide.
                existing.hide();
                activeSlots.remove(slotToken);
                CustomMusicToast toast = new CustomMusicToast(track, config, 0);
                tm.addToast(toast);
                activeSlots.add(slotToken);
            } else {
                existing.setTrack(track);
                existing.show();
            }
        } else {
            CustomMusicToast toast = new CustomMusicToast(track, config, 0);
            tm.addToast(toast);
            activeSlots.add(slotToken);
        }
        triggeredToastSlot = slotToken;
    }

    /** Hides the triggered toast (if any). */
    private static void hideTriggeredToast(Minecraft client) {
        if (client == null || client.gui == null || triggeredToastSlot == null) return;
        ToastManager tm = client.gui.toastManager();
        CustomMusicToast toast = tm.getToast(CustomMusicToast.class, triggeredToastSlot);
        if (toast != null) toast.hide();
        activeSlots.remove(triggeredToastSlot);
        triggeredToastSlot = null;
    }

    /**
     * Ensures the pause menu shows the current track. In non-ON_CHANGE
     * modes, the HUD toast may have faded; this re-adds it when the pause
     * menu opens. Called both from the tick (to keep the toast alive) and
     * from {@link #refreshSystemToastsOnPauseMenu()}.
     */
    private static void syncPauseMenuToast(Minecraft client, TrackInfo current) {
        if (client == null || client.gui == null) return;
        boolean inPauseMenu = client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen;
        if (!inPauseMenu || current == null) return;
        // In HUD_ONLY mode the pause menu shows no toast at all.
        if (config.systemToastLocation == McMusicConfig.SystemToastLocation.HUD_ONLY) return;

        ToastManager tm = client.gui.toastManager();
        String slotToken = "mcmusic-slot-0";
        CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
        if (existing == null) {
            CustomMusicToast toast = new CustomMusicToast(current, config, 0);
            toast.markReAddedForPauseMenu();
            tm.addToast(toast);
            activeSlots.add(slotToken);
        } else {
            existing.setTrack(current);
            existing.show(); // ensure visible while pause menu is open
        }
    }

    /**
     * Compares a system track against the in-game music, treating them as
     * duplicates when either display text is contained in the other.
     */
    private static boolean isDuplicateOfInGameMusic(TrackInfo systemTrack, Minecraft client) {
        try {
            String key = client.getMusicManager().getCurrentMusicTranslationKey();
            if (key == null) return false;
            String gameText = norm(Component.translatable(key.replace("/", ".")).getString());
            String systemText = norm(CustomMusicToast.formatDisplay(systemTrack));
            if (gameText.isEmpty() || systemText.isEmpty()) return false;
            if (gameText.equals(systemText)) return true;
            if (gameText.length() >= 4 && systemText.length() >= 4) {
                if (gameText.contains(systemText) || systemText.contains(gameText)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /**
     * Rebuilds the system toast list whenever the track order changes (for
     * multi-source mode). Uses <b>slot-based reuse</b>: instead of hiding
     * all and re-adding (which leaves old toasts animating out and occupying
     * slots), we find existing toasts by slot token and update their content
     * in-place. Only add/hide when the number of tracks changes.
     *
     * <p>In the pause menu, toasts are static — we don't re-arrange, just
     * fill empty slots for new tracks.</p>
     */
    private static void syncSystemToasts(Minecraft client, List<TrackInfo> tracks) {
        if (client == null || client.gui == null) return;
        ToastManager tm = client.gui.toastManager();

        boolean inPauseMenu = client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen;

        // In HUD_ONLY mode the pause menu must never show a toast — keep
        // everything hidden while the pause screen is open.
        if (inPauseMenu && config.systemToastLocation == McMusicConfig.SystemToastLocation.HUD_ONLY) {
            hideAllSystemToasts(client);
            return;
        }

        int needed = tracks.size();

        // Update or add toasts for each track.
        for (int i = 0; i < needed; i++) {
            String slotToken = "mcmusic-slot-" + i;
            TrackInfo track = tracks.get(i);
            CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
            if (existing != null) {
                // Update content in-place — no slot reassignment needed.
                existing.setTrack(track);
            } else {
                // Empty slot → add new toast here (top-most empty).
                CustomMusicToast toast = new CustomMusicToast(track, config, i);
                tm.addToast(toast);
                activeSlots.add(slotToken);
            }
        }

        // In the pause menu, don't hide toasts — leave them in place.
        if (inPauseMenu) return;

        // Hide toasts for slots that are no longer needed.
        int maxCheck = Math.max(needed, activeSlots.size()) + 2;
        for (int i = needed; i < maxCheck; i++) {
            String slotToken = "mcmusic-slot-" + i;
            CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
            if (existing != null) {
                existing.hide();
                activeSlots.remove(slotToken);
            }
        }
    }

    private static void hideAllSystemToasts(Minecraft client) {
        if (client == null || client.gui == null) return;
        ToastManager tm = client.gui.toastManager();
        for (String token : new ArrayList<>(activeSlots)) {
            CustomMusicToast old = tm.getToast(CustomMusicToast.class, token);
            if (old != null) old.hide();
        }
        activeSlots.clear();
    }

    /**
     * Called from {@code PauseScreenRefreshMixin.init()} when the pause
     * menu opens. Re-adds any active track whose toast has already faded
     * (HUD time-out) so the user can see it in the pause menu — but only
     * when {@code systemToastLocation} is {@code HUD_AND_PAUSE_MENU}.
     * In {@code HUD_ONLY} mode the pause menu shows nothing: we actively
     * hide any still-visible HUD toast (the ToastManager renders the same
     * visibleToasts on every screen, so without hiding it the toast would
     * keep showing in the pause menu until its 5s timeout).
     *
     * <p><b>为什么标记 reAddedForPauseMenu</b>：这里为暂停菜单补出来的弹窗，其 HUD
     * 原身其实早就超时消失了（进入暂停菜单前 HUD 已无弹窗）。这种"补"出来的弹窗离开
     * 暂停菜单时应直接消失，而不是继续在 HUD / 二级界面里再晃一遍——否则用户会觉得
     * 明明歌没变、弹窗却又冒出来了。</p>
     */
    public static void refreshSystemToastsOnPauseMenu() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) return;
        if (config.systemToastLocation == McMusicConfig.SystemToastLocation.HUD_ONLY) {
            hideAllSystemToasts(client);
            return;
        }
        ToastManager tm = client.gui.toastManager();

        if (config.multiSourceEnabled && SYSTEM.supportsMultiSource()) {
            // Multi-source: re-add all active tracks.
            List<TrackInfo> active = RecentTracksTracker.get().snapshot();
            for (int i = 0; i < active.size(); i++) {
                String slotToken = "mcmusic-slot-" + i;
                CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
                if (existing != null) {
                    existing.setTrack(active.get(i));
                    existing.show();
                    continue;
                }
                CustomMusicToast toast = new CustomMusicToast(active.get(i), config, i);
                toast.markReAddedForPauseMenu();
                tm.addToast(toast);
                activeSlots.add(slotToken);
            }
        } else {
            // Single-source: re-add the current track at slot 0.
            List<TrackInfo> active = RecentTracksTracker.get().snapshot();
            if (!active.isEmpty()) {
                String slotToken = "mcmusic-slot-0";
                CustomMusicToast existing = tm.getToast(CustomMusicToast.class, slotToken);
                if (existing != null) {
                    existing.setTrack(active.get(0));
                    existing.show();
                } else {
                    CustomMusicToast toast = new CustomMusicToast(active.get(0), config, 0);
                    toast.markReAddedForPauseMenu();
                    tm.addToast(toast);
                    activeSlots.add(slotToken);
                }
            }
        }
    }

    /**
     * Called from {@code PauseScreenRefreshMixin.onClose()} when the user
     * closes the pause menu.
     *
     * <p>Intentionally does nothing: the toast's display timer is global (it
     * keeps counting in the pause menu), so on return to the HUD the toast
     * simply keeps showing and slides out when the timer ends — matching the
     * vanilla NowPlayingToast behaviour. {@link CustomMusicToast#update} /
     * {@link ToastInstanceMixin} handle the "already timed out" case by
     * removing it instantly instead of sliding out.</p>
     */
    public static void reflowSystemToastsOnResume() {
        // No-op: leave toasts alone on pause-menu close.
    }

    /**
     * Called from {@code TitleScreenMixin.init()} when the title screen
     * opens — either on game launch or when returning to the title screen
     * from the world. Polls the current system track and shows a toast.
     * The title screen is treated like the HUD, so the toast slides in/out
     * and times out normally (see {@link CustomMusicToast#update}).
     */
    public static void showTitleScreenToast() {
        if (config == null || !config.enabled) return;
        if (config.sourceMode != McMusicConfig.SourceMode.SYSTEM
                && config.sourceMode != McMusicConfig.SourceMode.BOTH) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) return;

        // Poll on the background thread, then hop back to the main thread
        // to add the toast (UI must only be touched on the render thread).
        SYSTEM.pollAsync(track -> {
            if (track == null) return;
            client.execute(() -> {
                if (client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
                    showTriggeredToast(client, track);
                }
            });
        });
    }

    public static McMusicConfig config() { return config; }

    public static String systemBackendDescription() {
        return SYSTEM.backendDescription();
    }

    public static boolean isSystemMediaSupported() {
        return SYSTEM.isSupported();
    }

    /** True when the current OS can enumerate multiple system sources. */
    public static boolean isMultiSourceSupported() {
        return SYSTEM.supportsMultiSource();
    }

    public static String systemMediaDebugInfo() {
        return SYSTEM.debugInfo();
    }

    /** Returns the most recent single system track (for Alt+click debug). */
    public static TrackInfo currentSystemTrack() {
        List<TrackInfo> list = latestSystem.get();
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    public static void pollSystemMediaAsync(Consumer<TrackInfo> callback) {
        SYSTEM.pollAsync(callback);
    }

    /** Returns the list of installed macOS app names. */
    public static List<String> getMacAppNames() {
        return SYSTEM.getMacAppNames();
    }

    /** Update the macOS app allow-list and sync to the provider. */
    public static void setAllowedMacApps(List<String> apps) {
        if (config != null) {
            config.allowedMacApps = apps == null ? new ArrayList<>() : new ArrayList<>(apps);
            config.save();
        }
        SYSTEM.setAllowedMacApps(apps);
    }

    /** Returns the current macOS app allow-list (empty = all allowed). */
    public static List<String> getAllowedMacApps() {
        return config != null ? new ArrayList<>(config.allowedMacApps) : new ArrayList<>();
    }
}
