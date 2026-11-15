package dev.lumen.mixin;

import dev.lumen.module.ModuleManager;
import dev.lumen.module.impl.ZoomModule;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
	/** While zooming, the scroll wheel changes the zoom level instead of the hotbar slot. */
	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void lumen$scrollZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
		ZoomModule zoom = ModuleManager.get(ZoomModule.class);
		if (zoom != null && zoom.isEnabled() && zoom.handleScroll(vertical)) {
			ci.cancel();
		}
	}
}
