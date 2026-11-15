package dev.lumen.mixin;

import dev.lumen.module.ModuleManager;
import dev.lumen.module.impl.CloudsModule;
import dev.lumen.module.impl.CustomSkyModule;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void lumen$sky(Vec3d cameraPos, float tickDelta, CallbackInfoReturnable<Integer> cir) {
		CustomSkyModule sky = ModuleManager.get(CustomSkyModule.class);
		if (sky != null && sky.isEnabled()) {
			cir.setReturnValue(sky.tintSky(cir.getReturnValueI()));
		}
	}

	@Inject(method = "getCloudsColor", at = @At("RETURN"), cancellable = true)
	private void lumen$clouds(float tickDelta, CallbackInfoReturnable<Integer> cir) {
		CloudsModule clouds = ModuleManager.get(CloudsModule.class);
		if (clouds != null && clouds.isEnabled()) {
			cir.setReturnValue(clouds.tintClouds(cir.getReturnValueI()));
		}
	}
}
