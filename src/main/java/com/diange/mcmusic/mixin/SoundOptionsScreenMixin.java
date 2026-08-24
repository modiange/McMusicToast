package com.diange.mcmusic.mixin;

import com.diange.mcmusic.client.McMusicConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Hijacks the vanilla "Music Toast" cycle button in the Sound Options screen,
 * replacing it with a button that opens the McMusic config screen.
 *
 * <p>The vanilla {@code addSmall(musicFrequency, musicToast)} call is redirected:
 * {@code musicToast} is replaced by our custom "音乐弹窗设置…" button, while
 * {@code musicFrequency} and all other options are left untouched.</p>
 */
@Mixin(SoundOptionsScreen.class)
public class SoundOptionsScreenMixin {

    @Redirect(
            method = "addOptions",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V")
    )
    private void mcmusic$hijackMusicToastButton(OptionsList list, OptionInstance<?>[] options) {
        Minecraft mc = Minecraft.getInstance();
        OptionInstance<?> musicToast = mc.options.musicToast();

        // Check whether this particular addSmall call contains the musicToast option.
        boolean hasMusicToast = false;
        for (OptionInstance<?> opt : options) {
            if (opt == musicToast) { hasMusicToast = true; break; }
        }

        if (!hasMusicToast) {
            // Not the call we care about — reproduce the original behaviour by
            // creating buttons from the OptionInstances and adding them as widgets.
            List<AbstractWidget> widgets = new ArrayList<>();
            for (OptionInstance<?> opt : options) {
                widgets.add(opt.createButton(mc.options));
            }
            list.addSmall(widgets);
            return;
        }

        // Replace musicToast with our config button; keep the rest as-is.
        List<AbstractWidget> widgetList = new ArrayList<>();
        for (OptionInstance<?> opt : options) {
            if (opt == musicToast) {
                Screen parent = (Screen) (Object) this;
                widgetList.add(Button.builder(
                        Component.translatable("mcmusic.sound_options_button"),
                        b -> mc.gui.setScreen(new McMusicConfigScreen(parent))
                ).build());
            } else {
                widgetList.add(opt.createButton(mc.options));
            }
        }
        list.addSmall(widgetList);
    }
}
