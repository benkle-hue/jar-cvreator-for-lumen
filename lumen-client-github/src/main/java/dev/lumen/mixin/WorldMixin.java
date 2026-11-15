package dev.lumen.mixin;

import dev.lumen.module.ModuleManager;
import dev.lumen.module.impl.WeatherModule;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
	@Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
	private void lumen$rain(float delta, CallbackInfoReturnable<Float> cir) {
		if (!((Object) this instanceof ClientWorld)) return;
		WeatherModule weather = ModuleManager.get(WeatherModule.class);
		if (weather != null && weather.isEnabled()) {
			cir.setReturnValue(weather.rainLevel());
		}
	}

	@Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
	private void lumen$thunder(float delta, CallbackInfoReturnable<Float> cir) {
		if (!((Object) this instanceof ClientWorld)) return;
		WeatherModule weather = ModuleManager.get(WeatherModule.class);
		if (weather != null && weather.isEnabled()) {
			cir.setReturnValue(weather.thunderLevel());
		}
	}
}
