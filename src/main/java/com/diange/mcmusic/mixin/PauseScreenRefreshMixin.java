package com.diange.mcmusic.mixin;

import com.diange.mcmusic.client.McMusicClient;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-fires the system-media toasts whenever the pause screen is opened.
 *
 * <p>The vanilla {@code ToastManager} renders {@code visibleToasts} on every
 * screen (HUD + pause menu) — the same instance is drawn in both places.
 * HUD-side toasts are transient and may have already faded by the time the
 * user opens the pause menu. This mixin is a zero-draw hook that simply
 * re-adds any active tracks that have timed out, so the pause menu shows
 * the currently-playing system track again. No custom drawing — the
 * vanilla {@code ToastManager} does the rendering.</p>
 *
 * <p>On {@code onClose} (when the user closes the pause menu), it triggers
 * a re-flow of the toasts: the current track list is re-evaluated and
 * toasts are updated in-place. Removed tracks' toasts hide; new tracks
 * get added to the first empty slot. Existing toasts keep their content
 * and do not immediately disappear.</p>
 */
@Mixin(PauseScreen.class)
public class PauseScreenRefreshMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void mcmusic$refreshOnOpen(CallbackInfo ci) {
        McMusicClient.refreshSystemToastsOnPauseMenu();
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void mcmusic$reflowOnClose(CallbackInfo ci) {
        McMusicClient.reflowSystemToastsOnResume();
    }
}
