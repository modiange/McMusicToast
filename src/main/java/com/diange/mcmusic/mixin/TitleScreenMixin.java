package com.diange.mcmusic.mixin;

import com.diange.mcmusic.client.McMusicClient;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows a system-media toast whenever the title screen opens — both on game
 * launch and when returning to the title screen from the world.
 *
 * <p>This is a zero-draw hook: it just asks {@link McMusicClient} to poll
 * the current system track and show a toast. The title screen is treated
 * like the HUD, so the toast slides in/out and times out normally.</p>
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void mcmusic$onTitleScreen(CallbackInfo ci) {
        McMusicClient.showTitleScreenToast();
    }
}
