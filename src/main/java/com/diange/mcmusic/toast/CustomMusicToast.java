package com.diange.mcmusic.toast;

import com.diange.mcmusic.config.McMusicConfig;
import com.diange.mcmusic.media.TrackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MusicToastDisplayState;
import net.minecraft.client.color.ColorLerper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/**
 * 系统媒体（Spotify / Apple Music 等）的 Now Playing 弹窗。
 *
 * <p><b>设计意图</b>：系统媒体是用户主要关心的内容，所以在 HUD 中它占据第一行
 * （y=0），原版游戏音乐弹窗让位（游戏弹窗默认只在暂停菜单显示，仅在音乐切换时
 * 短暂出现在 HUD——由 {@code McMusicClient#updateVanillaMusicToastOption} 动态控制）。
 * 显示/消失动画模仿原版 NowPlayingToast：左→右滑入 → 显示 → 右→左滑出。</p>
 *
 * <p><b>计时全局</b>：显示时长由 vanilla 的 {@code fullyVisibleFor} 持续累积，
 * 切换画面（HUD / 暂停菜单 / 二级界面）不会重置——这就是"暂停游戏再回来、或切到
 * 设置/成就等页面时弹窗不消失"的原因。唯一例外：暂停菜单里只记录超时、不真正消失，
 * 离开后若已超时才立即消失（见 {@link #update}）。</p>
 *
 * <p><b>Slot-based reuse</b>：token 是固定 slot 标识（如 "mcmusic-slot-0"）而非
 * track stableId，让 {@code McMusicClient} 能原地更新已有弹窗，避免旧弹窗滑出时
 * 还占着 slot、导致新弹窗排错行。</p>
 */
public final class CustomMusicToast implements Toast {
    // Vanilla Now Playing toast uses these exact sprites.
    public static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/now_playing");
    public static final Identifier MUSIC_NOTES = Identifier.parse("icon/music_notes");

    // Layout constants matching vanilla NowPlayingToast.
    public static final int PADDING_LEFT = 30;
    public static final int PADDING_RIGHT = 7;
    public static final int BASE_HEIGHT = 30;
    public static final int ICON_X = 7;
    public static final int ICON_Y = 7;
    public static final int ICON_SIZE = 16;

    // Vanilla text color: DyeColor.LIGHT_GRAY.getTextColor()
    public static final int TEXT_COLOR = DyeColor.LIGHT_GRAY.getTextColor();

    // Color cycling — replicates NowPlayingToast.tickMusicNotes() behaviour.
    private static final long COLOR_CHANGE_FREQ_MS = 25L;
    private static int musicNoteColorTick = 0;
    private static long lastMusicNoteColorChange = 0L;
    private static int musicNoteColor = -1;

    // Width bounds. MAX_WIDTH accommodates up to 30 chars of title +
    // " - " + 30 chars of artist + padding (≈ 30*6 + 3*6 + 30*6 + 37 ≈ 415).
    public static final int MIN_WIDTH = 80;
    public static final int MAX_WIDTH = 420;

    /** Max characters for title / artist before ellipsis truncation. */
    public static final int MAX_TITLE_CHARS = 30;
    public static final int MAX_ARTIST_CHARS = 30;

    private static final String ELLIPSIS = "…";
    private static final String SEPARATOR = " - ";

    private TrackInfo track;
    private final McMusicConfig config;
    /**
     * Per-toast token; a fixed slot identifier (e.g.
     * {@code "mcmusic-slot-0"}) so the manager can find a specific toast
     * regardless of which track it currently displays.
     */
    private final Object token;
    private Visibility visibility = Visibility.SHOW;

    /** True once the display timer exceeded {@link McMusicConfig#displayDurationMs}
     *  while pinned in the pause menu. Used to decide, when leaving the pause
     *  menu, whether the toast should vanish immediately instead of sliding out. */
    private boolean timedOutInPauseMenu = false;

    /** When true, {@code ToastInstanceMixin} removes this toast on the same
     *  tick (skipping the 600ms slide-out). Set when leaving the pause menu
     *  after the display timer already expired. */
    private boolean instantHide = false;

    /** True when this toast was re-added <i>for the pause menu</i> because the
     *  HUD toast had already faded (i.e. there was no toast right before the
     *  pause menu opened). Such a toast disappears immediately on leaving the
     *  pause menu instead of continuing to show. */
    private boolean reAddedForPauseMenu = false;

    /**
     * @param track  initial track data
     * @param config mod config
     * @param slotIndex  fixed slot index (0-based) used as the token
     */
    public CustomMusicToast(TrackInfo track, McMusicConfig config, int slotIndex) {
        this.track = track;
        this.config = config;
        this.token = "mcmusic-slot-" + slotIndex;
    }

    /** Legacy constructor — uses slot 0. */
    public CustomMusicToast(TrackInfo track, McMusicConfig config) {
        this(track, config, 0);
    }

    /** Update the track data displayed by this toast (no re-add needed).
     *  Visibility is only reset to SHOW when the track's stableId changes,
     *  so repeated updates of the same track don't restart the hide timer. */
    public void setTrack(TrackInfo newTrack) {
        boolean idChanged = this.track == null || newTrack == null
                || !this.track.stableId().equals(newTrack.stableId());
        this.track = newTrack;
        if (idChanged) {
            this.visibility = Visibility.SHOW;
        }
    }

    /** Force visibility back to SHOW (used when the toast should re-appear
     *  even though the track itself hasn't changed, e.g. ON_RESUME). */
    public void show() {
        this.visibility = Visibility.SHOW;
    }

    /** Returns the track currently displayed by this toast. */
    public TrackInfo getTrack() {
        return track;
    }

    /** True when {@code ToastInstanceMixin} should remove this toast on the
     *  same tick instead of waiting for the slide-out animation. */
    public boolean shouldInstantHide() {
        return instantHide;
    }

    /** Marks this toast as re-added for the pause menu (HUD toast had faded). */
    public void markReAddedForPauseMenu() {
        this.reAddedForPauseMenu = true;
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public void update(ToastManager manager, long time) {
        // 显示 / 消失逻辑（模仿原版 NowPlayingToast 的「计时全局」特性）：
        // 计时器（time = fullyVisibleFor）由 vanilla 持续累积，切换画面不会重置。
        //  - 暂停菜单（PauseScreen）+ HUD_AND_PAUSE_MENU：
        //        静止 pinned，计时继续但不消失（记录是否已超时，供离开时判断）。
        //  - 暂停菜单（PauseScreen）+ HUD_ONLY：
        //        立即隐藏（暂停菜单不显示系统弹窗）。
        //  - 离开暂停菜单后（回到 HUD / 进入标题 / 二级界面）：
        //        - 若这个 toast 是为暂停菜单重新添加的（进入暂停菜单前 HUD 已无弹窗）
        //          → 立即消失（跳过滑出），不再在别处继续显示。
        //        - 否则计时结束后隐藏；若之前在暂停菜单已超时（timedOutInPauseMenu），
        //          则标记 instantHide 立即消失；否则走正常的右→左滑出。
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc != null ? mc.gui.screen() : null;
        boolean inPauseMenu = screen instanceof PauseScreen;
        boolean persistInPauseMenu = config.systemToastLocation
                == McMusicConfig.SystemToastLocation.HUD_AND_PAUSE_MENU;

        if (inPauseMenu && persistInPauseMenu) {
            // 暂停菜单 pinned：计时继续但静止不动、不消失。
            if (time >= config.displayDurationMs) {
                timedOutInPauseMenu = true;
            }
        } else if (inPauseMenu) {
            // HUD_ONLY 下的暂停菜单：立即隐藏。
            visibility = Visibility.HIDE;
        } else if (reAddedForPauseMenu) {
            // 离开暂停菜单，且此 toast 是为暂停菜单重新添加的
            // （进入前 HUD 已无弹窗）→ 立即消失。
            visibility = Visibility.HIDE;
            instantHide = true;
        } else if (time >= config.displayDurationMs) {
            // HUD / 标题 / 二级界面：计时结束 → 隐藏。
            visibility = Visibility.HIDE;
            if (timedOutInPauseMenu) {
                // 之前在暂停菜单已超时，现在离开 → 立即消失（跳过滑出）。
                instantHide = true;
            }
        }
        tickMusicNotes();
    }

    /** Immediately request hide (used when the slot is no longer needed). */
    public void hide() {
        this.visibility = Visibility.HIDE;
    }

    /** Replicates the vanilla color-cycling animation for the music notes icon. */
    public static void tickMusicNotes() {
        long now = System.currentTimeMillis();
        if (now - lastMusicNoteColorChange >= COLOR_CHANGE_FREQ_MS) {
            musicNoteColorTick++;
            lastMusicNoteColorChange = now;
            musicNoteColor = ColorLerper.getLerpedColor(ColorLerper.Type.MUSIC_NOTE, musicNoteColorTick);
        }
    }

    /** Returns the display text: "{title} - {artist}" or just "{title}". */
    public String displayText() {
        return formatDisplay(track);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, Font font, long fullyVisibleForMs) {
        renderTrackAt(g, font, 0, 0, track);
    }

    /**
     * Render a single track at the given screen-space top-left coordinate
     * using the vanilla {@code now_playing} toast look.
     *
     * <p>Title and artist are drawn as <b>independent</b> text segments,
     * each truncated to {@value #MAX_TITLE_CHARS} / {@value #MAX_ARTIST_CHARS}
     * characters respectively. When the artist is missing or "Unknown", the
     * separator is omitted entirely.</p>
     */
    public static void renderTrackAt(GuiGraphicsExtractor g, Font font, int x, int y, TrackInfo track) {
        String title = truncateChars(track.title(), MAX_TITLE_CHARS);
        String artist = formatArtist(track.artist(), MAX_ARTIST_CHARS);

        int titleW = font.width(title);
        int sepW = artist.isEmpty() ? 0 : font.width(SEPARATOR);
        int artistW = artist.isEmpty() ? 0 : font.width(artist);
        int textW = titleW + sepW + artistW;

        int w = Math.min(Math.max(PADDING_LEFT + textW + PADDING_RIGHT, MIN_WIDTH), MAX_WIDTH);
        int h = BASE_HEIGHT;

        g.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, w, h);

        tickMusicNotes();
        g.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES,
                x + ICON_X, y + ICON_Y, ICON_SIZE, ICON_SIZE, musicNoteColor);

        int maxTextWidth = w - PADDING_LEFT - PADDING_RIGHT;
        int mainY = y + 15 - font.lineHeight / 2;

        // Draw title (pixel-truncate as safety net if char-truncated text
        // still exceeds the available width).
        String drawTitle = truncateWithEllipsis(font, title, maxTextWidth);
        g.text(font, Component.literal(drawTitle), x + PADDING_LEFT, mainY, TEXT_COLOR);

        // Draw separator + artist only when artist is present.
        if (!artist.isEmpty()) {
            int afterTitle = x + PADDING_LEFT + font.width(drawTitle);
            int remaining = maxTextWidth - font.width(drawTitle);
            String drawSep = truncateWithEllipsis(font, SEPARATOR, remaining);
            g.text(font, Component.literal(drawSep), afterTitle, mainY, TEXT_COLOR);
            afterTitle += font.width(drawSep);
            remaining -= font.width(drawSep);
            String drawArtist = truncateWithEllipsis(font, artist, Math.max(0, remaining));
            g.text(font, Component.literal(drawArtist), afterTitle, mainY, TEXT_COLOR);
        }
    }

    /**
     * Returns the display text: "{title} - {artist}" (each char-truncated)
     * or just "{title}" when artist is absent.
     */
    public static String formatDisplay(TrackInfo t) {
        if (t == null) return "";
        String title = truncateChars(t.title(), MAX_TITLE_CHARS);
        String artist = formatArtist(t.artist(), MAX_ARTIST_CHARS);
        if (artist.isEmpty()) return title;
        return title + SEPARATOR + artist;
    }

    /**
     * Returns the artist string for display, or empty string when the
     * artist is missing / "Unknown".
     */
    private static String formatArtist(String artist, int maxChars) {
        if (artist == null || artist.isBlank() || "Unknown".equals(artist)) return "";
        return truncateChars(artist, maxChars);
    }

    /** Truncates text to at most {@code maxChars} characters, appending
     *  an ellipsis if truncated. */
    public static String truncateChars(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 1) + ELLIPSIS;
    }

    /** Truncates text to fit within maxWidth (pixels), appending an ellipsis. */
    public static String truncateWithEllipsis(Font font, String text, int maxWidth) {
        if (text == null) text = "";
        if (font.width(text) <= maxWidth) return text;
        int ellipsisWidth = font.width(ELLIPSIS);
        if (maxWidth <= ellipsisWidth) return ELLIPSIS;
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + ELLIPSIS;
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int textW = font.width(displayText());
        return Math.min(Math.max(PADDING_LEFT + textW + PADDING_RIGHT, MIN_WIDTH), MAX_WIDTH);
    }

    @Override
    public int height() {
        return BASE_HEIGHT;
    }

    /**
     * 原版 NowPlayingToast 的水平位置：{@code width() * visiblePortion - width()}。
     * 暂停菜单（PauseScreen）返回 0（静止 pinned，不滑动）；其他画面（HUD /
     * 标题 / 二级界面）启用「左→右滑入 / 右→左滑出」动画：
     *  <ul>
     *    <li>visiblePortion 0→1（600ms 滑入）：x 从 -width 平滑到 0（左往右出现）。</li>
     *    <li>visiblePortion 1→0（600ms 滑出）：x 从 0 平滑到 -width（右往左消失）。</li>
     *  </ul>
     */
    @Override
    public float xPos(int screenWidth, float visiblePortion) {
        if (isInPauseMenu()) {
            return 0;
        }
        return width() * visiblePortion - width();
    }

    /** 当前是否处于暂停菜单（PauseScreen）。 */
    private static boolean isInPauseMenu() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.gui.screen() instanceof PauseScreen;
    }

    /**
     * Vertical position. Stacks via {@code firstSlotIndex * BASE_HEIGHT}, and
     * offsets one row when the vanilla {@code NowPlayingToast} (game music)
     * is actually rendered on the current screen — so the system toast sits
     * directly underneath it instead of overlapping.
     */
    @Override
    public float yPos(int firstSlotIndex) {
        float baseOffset = isGameToastRenderedHere() ? BASE_HEIGHT : 0f;
        return baseOffset + firstSlotIndex * BASE_HEIGHT;
    }

    /**
     * Whether the vanilla game-music toast is rendered on the current screen:
     *  <ul>
     *    <li>暂停菜单：musicToast.renderInPauseScreen()（PAUSE / PAUSE_AND_TOAST）。</li>
     *    <li>HUD / 二级界面：musicToast.renderToast()（仅 PAUSE_AND_TOAST）。</li>
     *  </ul>
     * 这样在 HUD 中游戏弹窗默认不显示（musicToast=PAUSE），系统弹窗占据第一位。
     */
    private static boolean isGameToastRenderedHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        Screen screen = mc.gui.screen();
        MusicToastDisplayState state = mc.options.musicToast().get();
        if (screen instanceof PauseScreen) {
            return state.renderInPauseScreen();
        }
        return state.renderToast();
    }
}
