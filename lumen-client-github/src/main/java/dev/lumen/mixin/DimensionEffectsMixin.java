package dev.lumen.mixin;

import dev.lumen.module.ModuleManager;
import dev.lumen.module.impl.CloudsModule;
import net.minecraft.client.render.DimensionEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DimensionEffects.class)
public abstract class DimensionEffectsMixin {
	@Inject(method = "getCloudsHeight", at = @At("RETURN"), cancellable = true)
	private void lumen$cloudHeight(CallbackInfoReturnable<Float> cir) {
		CloudsModule clouds = ModuleManager.get(CloudsModule.class);
		// Only change the overworld clouds; NaN means "no clouds" in other dimensions.
		if (clouds != null && clouds.isEnabled() && !Float.isNaN(cir.getReturnValueF())) {
			cir.setReturnValue(clouds.height());
		}
	}
}
