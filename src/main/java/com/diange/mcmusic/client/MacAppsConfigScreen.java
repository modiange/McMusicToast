package com.diange.mcmusic.client;

import com.diange.mcmusic.media.MacAppRegistry;
import com.diange.mcmusic.media.MacAppRegistry.AppEntry;
import com.diange.mcmusic.media.MacAppRegistry.AuthState;
import com.diange.mcmusic.media.MacAppRegistry.Category;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * macOS media-app allow-list settings, styled like the vanilla Game Rules
 * panel.
 *
 * <p>Layout (via {@link OptionsSubScreen}'s {@code HeaderAndFooterLayout}):</p>
 * <ul>
 *   <li><b>Header:</b> title + search box.</li>
 *   <li><b>Content (scrollable OptionsList):</b> toggle buttons grouped by
 *       category — 音乐软件 / 浏览器 / 播放器. All known apps are listed;
 *       not-installed apps are greyed out.</li>
 *   <li><b>Footer:</b> macOS Settings (→ Privacy &amp; Security → Automation)
 *       and Done. No Cancel — the macOS Settings button replaces it.</li>
 * </ul>
 *
 * <p>Each row has a small coloured indicator on the left for quick
 * at-a-glance status:</p>
 * <ul>
 *   <li><span style="color:lime">●</span> Green — INSTALLED + AUTHORIZED
 *       (free toggle).</li>
 *   <li><span style="color:gold">●</span> Yellow — INSTALLED + UNKNOWN
 *       (needs permission — clickable but will prompt).</li>
 *   <li><span style="color:red">●</span> Red — INSTALLED + DENIED (greyed
 *       out, must grant via macOS Settings).</li>
 *   <li><span style="color:gray">●</span> Grey — NOT INSTALLED (greyed
 *       out, can't enable).</li>
 * </ul>
 *
 * <p>"Done" saves and returns to the parent screen ({@link McMusicConfigScreen}).
 * The macOS Settings button opens System Settings → Privacy &amp; Security →
 * Automation.</p>
 */
public final class MacAppsConfigScreen extends OptionsSubScreen {

    private static final int TOGGLE_WIDTH = 310; // vanilla big-button width

    /** Working copy of the enabled-apps set (edited locally, saved on Done). */
    private Set<String> workingEnabled;
    /** Snapshot of enabled-apps at screen-open time (for cancel-style behaviour). */
    private final Set<String> originalEnabled;

    /** Current search query (preserved across widget rebuilds). */
    private String searchQuery = "";

    /** Background auth-check thread. */
    private Thread authCheckThread;

    public MacAppsConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("mcmusic.mac_apps.title"));
        List<String> allowed = McMusicClient.getAllowedMacApps();
        List<String> all = McMusicClient.getMacAppNames();
        if (allowed.isEmpty()) {
            this.originalEnabled = new HashSet<>(all);
        } else {
            this.originalEnabled = new HashSet<>(allowed);
        }
        this.workingEnabled = new HashSet<>(this.originalEnabled);
    }

    /**
     * Clears the header/contents/footer frames before re-initialising.
     * {@code OptionsSubScreen}'s {@code HeaderAndFooterLayout} only ever
     * <i>adds</i> children, so without this a {@code rebuildWidgets()}
     * (search input / auth refresh) would accumulate duplicate headers,
     * search boxes and lists — the visible "repeats while scrolling" bug.
     */
    @Override
    protected void init() {
        layout.removeChildren();
        super.init();
    }

    @Override
    protected void addTitle() {
        // Title header (same as vanilla).
        layout.addTitleHeader(this.title, this.font);

        // Search box below the title.
        EditBox searchBox = new EditBox(this.font, 200, 20,
                Component.translatable("mcmusic.mac_apps.search"));
        searchBox.setHint(Component.translatable("mcmusic.mac_apps.search.hint"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(text -> {
            searchQuery = text;
            rebuildWidgets();
        });
        layout.addToHeader(searchBox);
    }

    @Override
    protected void addOptions() {
        // 页面上方的白色字标题。
        list.addHeader(Component.translatable("mcmusic.mac_apps.list_header")
                .withStyle(ChatFormatting.WHITE));

        // Use ALL known apps (not just installed) so the user can see
        // what's available. Not-installed apps are greyed out.
        List<AppEntry> all = MacAppRegistry.getAllApps();
        String query = searchQuery == null ? "" : searchQuery.toLowerCase();

        // Group by category.
        for (Category cat : Category.values()) {
            List<AppEntry> inCat = new ArrayList<>();
            for (AppEntry app : all) {
                if (app.category != cat) continue;
                if (!query.isEmpty() && !app.name.toLowerCase().contains(query)) continue;
                inCat.add(app);
            }
            if (inCat.isEmpty()) continue;

            // Category header.
            list.addHeader(Component.translatable("mcmusic.mac_apps.category." + cat.name().toLowerCase()));

            // One toggle per app.
            for (AppEntry app : inCat) {
                list.addBig(createAppToggle(app));
            }
        }

        // If nothing matches the search, show a placeholder.
        if (list.children().isEmpty()) {
            list.addHeader(Component.translatable("mcmusic.mac_apps.no_results"));
        }

        // Kick off a background auth check (once per screen open).
        if (authCheckThread == null || !authCheckThread.isAlive()) {
            startAuthCheck();
        }
    }

    /**
     * Creates a toggle button for an app. Behaviour depends on auth state
     * and installation status:
     * <ul>
     *   <li>Not installed → toggle greyed out, tooltip "未安装"</li>
     *   <li>Installed + Unknown → toggle clickable, tooltip "需要权限"</li>
     *   <li>Installed + Denied → toggle greyed out, tooltip "已拒绝"</li>
     *   <li>Installed + Authorized → free toggle</li>
     * </ul>
     */
    private Button createAppToggle(AppEntry app) {
        AuthState auth = MacAppRegistry.getAuthState(app.name);
        boolean installed = MacAppRegistry.isInstalled(app);
        boolean enabled = workingEnabled.contains(app.name);

        // Prefix with an emoji-style indicator that maps to the auth state.
        // The Unicode block element renders consistently across platforms.
        Component label = toggleText(app.name, enabled, auth, installed);

        Button btn = Button.builder(label, b -> {
            if (!installed) return;
            AuthState currentAuth = MacAppRegistry.getAuthState(app.name);
            if (currentAuth == AuthState.DENIED) return;
            if (currentAuth == AuthState.UNKNOWN) {
                // 需要权限：点击时弹出该软件的 macOS 自动化授权对话框，
                // 授权完成后自动刷新按钮状态。
                requestAuth(app);
                return;
            }
            // Authorized — free toggle.
            if (workingEnabled.contains(app.name)) {
                workingEnabled.remove(app.name);
            } else {
                workingEnabled.add(app.name);
            }
            b.setMessage(toggleText(app.name, workingEnabled.contains(app.name),
                    currentAuth, installed));
        }).width(TOGGLE_WIDTH).build();

        // Disable for uninstalled or denied apps.
        if (!installed) {
            btn.active = false;
            btn.setTooltip(Tooltip.create(Component.translatable(
                    "mcmusic.mac_apps.not_installed.tooltip")));
        } else if (auth == AuthState.DENIED) {
            btn.active = false;
            btn.setTooltip(Tooltip.create(Component.translatable(
                    "mcmusic.mac_apps.denied.tooltip")));
        } else if (auth == AuthState.UNKNOWN) {
            btn.setTooltip(Tooltip.create(Component.translatable(
                    "mcmusic.mac_apps.unknown.tooltip")));
        } else {
            btn.setTooltip(Tooltip.create(Component.translatable(
                    "mcmusic.mac_apps.authorized.tooltip")));
        }

        return btn;
    }

    private static Component toggleText(String appName, boolean on, AuthState auth, boolean installed) {
        String stateKey;
        if (!installed) {
            stateKey = "mcmusic.mac_apps.state.not_installed";
        } else if (auth == AuthState.DENIED) {
            stateKey = "mcmusic.mac_apps.state.denied";
        } else if (auth == AuthState.UNKNOWN) {
            stateKey = "mcmusic.mac_apps.state.unknown";
        } else {
            stateKey = on ? "mcmusic.on" : "mcmusic.off";
        }
        // Prefix with a colour-coded block character for at-a-glance status.
        String prefix = indicatorPrefix(installed, auth);
        return Component.literal(prefix + appName + ": ")
                .append(Component.translatable(stateKey));
    }

    @Override
    protected void addFooter() {
        // Footer: [macOS 设置…] [完成] — no Cancel button.
        LinearLayout footer = LinearLayout.horizontal().spacing(8);

        Button settingsBtn = Button.builder(
                Component.translatable("mcmusic.mac_apps.macos_settings"),
                b -> openMacAutomationSettings()
        ).tooltip(Tooltip.create(Component.translatable(
                "mcmusic.mac_apps.macos_settings.tooltip")))
                .width(140).build();

        Button doneBtn = Button.builder(CommonComponents.GUI_DONE, b -> {
            McMusicClient.setAllowedMacApps(new ArrayList<>(workingEnabled));
            onClose();
        }).width(140).build();

        footer.addChild(settingsBtn);
        footer.addChild(doneBtn);
        layout.addToFooter(footer);
    }

    /** Opens macOS System Settings → Privacy & Security → Automation. */
    private void openMacAutomationSettings() {
        try {
            String uri = "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation";
            Runtime.getRuntime().exec(new String[]{"open", uri});
        } catch (Throwable ignored) {
            try {
                Runtime.getRuntime().exec(new String[]{"open", "x-apple.systempreferences:com.apple.preference.security"});
            } catch (Exception ignored2) {}
        }
    }

    /**
     * Spawns a background thread that checks the AppleScript automation
     * permission for each installed app (only running apps are checked —
     * checking non-running apps would risk launching them). When the check
     * completes, the widgets are rebuilt on the next client tick to reflect
     * the updated auth states.
     */
    private void startAuthCheck() {
        final List<AppEntry> apps = MacAppRegistry.getAllApps();
        authCheckThread = new Thread(() -> {
            for (AppEntry app : apps) {
                if (MacAppRegistry.isInstalled(app)) {
                    MacAppRegistry.refreshAuthState(app.name);
                }
            }
            needsRebuild = true;
        }, "mcmusic-auth-check");
        authCheckThread.setDaemon(true);
        authCheckThread.start();
    }

    /** Flag set by the background auth-check thread; consumed by tick(). */
    private volatile boolean needsRebuild = false;

    /**
     * Triggers the macOS automation permission dialog for an app (by sending
     * a no-op tell) and rebuilds the widgets once it resolves. Runs on a
     * background thread because {@code osascript} blocks while the dialog is
     * open.
     */
    private void requestAuth(AppEntry app) {
        Thread t = new Thread(() -> {
            MacAppRegistry.requestAuthorization(app.name);
            needsRebuild = true;
        }, "mcmusic-auth-request");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) {
            needsRebuild = false;
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        if (authCheckThread != null) {
            authCheckThread.interrupt();
        }
        super.onClose();
    }

    /**
     * Visual indicator prefix for the toggle label. Returns a coloured
     * block character (█) that renders consistently across platforms.
     * Makes the current status scannable at a glance without hovering.
     */
    private static String indicatorPrefix(boolean installed, AuthState auth) {
        if (!installed) return "§8▌ "; // grey — not installed
        if (auth == AuthState.AUTHORIZED) return "§a▌ "; // green
        if (auth == AuthState.DENIED) return "§c▌ "; // red
        return "§e▌ "; // yellow — unknown
    }
}
