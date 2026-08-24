package com.diange.mcmusic.mixin;

import com.diange.mcmusic.toast.CustomMusicToast;
import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在弹窗自己请求"立即消失"时（{@link CustomMusicToast#shouldInstantHide()}），
 * 当帧就把它移除，跳过 vanilla 默认的 600ms 滑出动画。
 *
 * <p><b>为什么只做这一件事</b>：这里刻意不碰 {@code animationStartTime} /
 * {@code becameFullyVisibleAt} / {@code visiblePortion} 这些计时字段——让 vanilla
 * 自己跑完 600ms 滑入、计时、600ms 滑出，是最稳的做法（历史上曾因手动改
 * {@code animationStartTime} 用错时间基准，导致弹窗永不消失）。所有"该显示还是该消失"
 * 的屏幕判断都放在 {@link CustomMusicToast} 里，mixin 只负责"确定要消失时立刻消失"。</p>
 *
 * <p>通过 {@code @Mixin(targets = ...)} 定位 package-private 内部类，用 {@code @Shadow}
 * 取字段，让 Loom 正确重映射字段名（反射方案会因运行时混淆静默失败）。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public abstract class ToastInstanceMixin {

    @Shadow
    private Toast toast;

    @Shadow
    private float visiblePortion;

    @Shadow
    protected boolean hasFinishedRendering;

    @Inject(method = "update", at = @At("TAIL"))
    private void mcmusic$instantHide(CallbackInfo ci) {
        if (!(toast instanceof CustomMusicToast c)) return;
        if (c.shouldInstantHide()) {
            this.visiblePortion = 0.0f;
            this.hasFinishedRendering = true;
        }
    }
}
