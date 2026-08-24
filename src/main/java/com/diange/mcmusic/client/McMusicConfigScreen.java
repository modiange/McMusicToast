package com.diange.mcmusic.client;

import com.diange.mcmusic.config.McMusicConfig;
import com.diange.mcmusic.media.TrackInfo;
import com.diange.mcmusic.toast.CustomMusicToast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Mod config screen.
 *
 * <p>Layout:</p>
 * <ul>
 *   <li>Row 1: [音乐弹窗：开] [音乐来源：…]  (small, two-per-row)</li>
 *   <li>Row 2: [显示位置：…] [弹窗触发：…]  (small, two-per-row)</li>
 *   <li>Big row: [允许的音乐软件…] or [多来源显示：…]  (depends on OS)</li>
 * </ul>
 *
 * <p>Footer: [预览弹窗] [完成].</p>
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li>When the mod is disabled, all setting buttons are greyed out.</li>
 *   <li>On unsupported OS, source is locked to MINECRAFT.</li>
 * </ul>
 */
public final class McMusicConfigScreen extends OptionsSubScreen {

    private static final int SMALL_BTN_WIDTH = 150; // vanilla small-button width (half of 310)
    private static final int BIG_BTN_WIDTH = 310;   // vanilla big-button width

    private final McMusicConfig cfg = McMusicClient.config();
    private final boolean systemSupported = McMusicClient.isSystemMediaSupported();

    /** Buttons whose active state depends on the enabled toggle. */
    private Button sourceBtn, locationBtn, triggerBtn, multiSourceBtn, previewBtn;

    public McMusicConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("mcmusic.title"));
    }

    @Override
    protected void addOptions() {
        // --- Row 1: Enabled + Source mode (two small buttons) ---
        Button enabledBtn = Button.builder(status("mcmusic.enabled", cfg.enabled), b -> {
            cfg.enabled = !cfg.enabled;
            b.setMessage(status("mcmusic.enabled", cfg.enabled));
            cfg.save();
            McMusicClient.updateVanillaMusicToastOption();
            refreshButtonStates();
        }).tooltip(Tooltip.create(Component.translatable("mcmusic.enabled.tooltip"))).width(SMALL_BTN_WIDTH).build();

        if (systemSupported) {
            sourceBtn = Button.builder(sourceText(), b -> {
                cfg.sourceMode = McMusicConfig.SourceMode.values()[(cfg.sourceMode.ordinal() + 1) % McMusicConfig.SourceMode.values().length];
                b.setMessage(sourceText());
                cfg.save();
                McMusicClient.updateVanillaMusicToastOption();
                refreshButtonStates();
            }).tooltip(Tooltip.create(Component.translatable("mcmusic.source.tooltip"))).width(SMALL_BTN_WIDTH).build();
        } else {
            // Unsupported OS: lock to MINECRAFT, grey out.
            cfg.sourceMode = McMusicConfig.SourceMode.MINECRAFT;
            sourceBtn = Button.builder(
                    Component.translatable("mcmusic.source", Component.translatable("mcmusic.source.minecraft")),
                    b -> {}
            ).tooltip(Tooltip.create(Component.translatable("mcmusic.source.unsupported.tooltip")))
                    .width(SMALL_BTN_WIDTH).build();
            sourceBtn.active = false;
        }
        list.addSmall(enabledBtn, sourceBtn);

        // --- Row 2: Display location + System display trigger ---
        locationBtn = Button.builder(locationText(), b -> {
            cfg.systemToastLocation = McMusicConfig.SystemToastLocation.values()[(cfg.systemToastLocation.ordinal() + 1) % McMusicConfig.SystemToastLocation.values().length];
            b.setMessage(locationText());
            cfg.save();
        }).tooltip(Tooltip.create(Component.translatable("mcmusic.location.tooltip")))
                .width(SMALL_BTN_WIDTH).build();

        triggerBtn = Button.builder(triggerText(), b -> {
            cfg.systemDisplayMode = McMusicConfig.SystemDisplayMode.values()[(cfg.systemDisplayMode.ordinal() + 1) % McMusicConfig.SystemDisplayMode.values().length];
            b.setMessage(triggerText());
            cfg.save();
        }).tooltip(Tooltip.create(Component.translatable("mcmusic.trigger.tooltip")))
                .width(SMALL_BTN_WIDTH).build();
        list.addSmall(locationBtn, triggerBtn);

        // --- Big row: Multi-source / macOS app allow-list entry ---
        if (systemSupported && McMusicClient.isMultiSourceSupported()) {
            // Windows/Linux: multi-source toggle
            multiSourceBtn = Button.builder(multiSourceText(), b -> {
                cfg.multiSourceEnabled = !cfg.multiSourceEnabled;
                b.setMessage(multiSourceText());
                cfg.save();
            }).tooltip(Tooltip.create(Component.translatable("mcmusic.multi_source.tooltip",
                    String.valueOf(cfg.pauseMaxTracks))))
                    .width(BIG_BTN_WIDTH).build();
        } else if (systemSupported) {
            // macOS: secondary config for which media apps to read from.
            multiSourceBtn = Button.builder(
                    Component.translatable("mcmusic.mac_apps.entry"),
                    b -> minecraft.gui.setScreen(new MacAppsConfigScreen(this))
            ).tooltip(Tooltip.create(Component.translatable("mcmusic.mac_apps.entry.tooltip")))
                    .width(BIG_BTN_WIDTH).build();
        } else {
            multiSourceBtn = Button.builder(
                    Component.translatable("mcmusic.multi_source", Component.translatable("mcmusic.multi_source.unsupported")),
                    b -> {}
            ).tooltip(Tooltip.create(Component.translatable("mcmusic.multi_source.unsupported.tooltip")))
                    .width(BIG_BTN_WIDTH).build();
            multiSourceBtn.active = false;
            cfg.multiSourceEnabled = false;
        }
        list.addBig(multiSourceBtn);

        // Apply initial enabled/disabled state.
        refreshButtonStates();
    }

    /**
     * Footer: "Preview" (left) + "Done" (right). Both width 150.
     */
    @Override
    protected void addFooter() {
        LinearLayout footer = LinearLayout.horizontal().spacing(10);
        previewBtn = Button.builder(Component.translatable("mcmusic.test"), b -> {
            minecraft.gui.toastManager().addToast(new CustomMusicToast(
                    new TrackInfo("McMusic Preview", "diange", "McMusic", "Preview", "", "preview"), cfg));
        }).tooltip(Tooltip.create(Component.translatable("mcmusic.test.tooltip"))).width(SMALL_BTN_WIDTH).build();

        Button doneBtn = Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .width(SMALL_BTN_WIDTH).build();

        footer.addChild(previewBtn);
        footer.addChild(doneBtn);
        layout.addToFooter(footer);
        if (previewBtn != null) previewBtn.active = cfg.enabled;
    }

    /** Updates active state of all dependent buttons based on enabled flag. */
    private void refreshButtonStates() {
        boolean active = cfg.enabled;
        if (sourceBtn != null && systemSupported) sourceBtn.active = active;
        if (locationBtn != null && systemSupported) locationBtn.active = active;
        if (triggerBtn != null && systemSupported) triggerBtn.active = active;
        if (multiSourceBtn != null && systemSupported) multiSourceBtn.active = active;
        if (previewBtn != null) previewBtn.active = active;
    }

    // ---- Text helpers ----

    private Component status(String key, boolean value) {
        return Component.translatable(key, value ? Component.translatable("mcmusic.on") : Component.translatable("mcmusic.off"));
    }

    private Component sourceText() {
        return Component.translatable("mcmusic.source", Component.translatable("mcmusic.source." + cfg.sourceMode.name().toLowerCase()));
    }

    private Component locationText() {
        return Component.translatable("mcmusic.location", Component.translatable("mcmusic.location." + cfg.systemToastLocation.name().toLowerCase()));
    }

    private Component triggerText() {
        return Component.translatable("mcmusic.trigger", Component.translatable("mcmusic.trigger." + cfg.systemDisplayMode.name().toLowerCase()));
    }

    private Component multiSourceText() {
        return Component.translatable("mcmusic.multi_source", Component.translatable(cfg.multiSourceEnabled ? "mcmusic.on" : "mcmusic.off"));
    }
}
