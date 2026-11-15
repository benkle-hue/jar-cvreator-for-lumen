package dev.lumen.module.impl;

import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;

/**
 * Client-side weather. Only changes what you see and hear; the server's real weather is untouched.
 * Rain/snow still follow the biome (deserts stay dry, cold biomes get snow).
 */
public final class WeatherModule extends ClientModule {
	private final Setting weather = add(Setting.mode("Weather", "Rain", "Clear", "Rain", "Thunder"));
	private final Setting intensity = add(Setting.number("Intensity", 1.0, 0.1, 1.0, 0.1));

	private float rain;
	private float thunder;

	public WeatherModule() {
		super("Weather", "Force clear skies, rain or a thunderstorm on your screen only.", Category.VISUALS);
		status = "Visual only. The server's weather does not change.";
	}

	private float targetRain() {
		return weather.getMode().equals("Clear") ? 0f : (float) intensity.getNumber();
	}

	private float targetThunder() {
		return weather.getMode().equals("Thunder") ? (float) intensity.getNumber() : 0f;
	}

	/** Eased value so changing the mode fades the weather in/out instead of snapping. */
	public float rainLevel() {
		rain += (targetRain() - rain) * 0.02f;
		if (Math.abs(targetRain() - rain) < 0.002f) rain = targetRain();
		return rain;
	}

	public float thunderLevel() {
		thunder += (targetThunder() - thunder) * 0.02f;
		if (Math.abs(targetThunder() - thunder) < 0.002f) thunder = targetThunder();
		return thunder;
	}
}
