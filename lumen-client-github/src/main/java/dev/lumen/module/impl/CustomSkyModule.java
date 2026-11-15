package dev.lumen.module.impl;

import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.time.LocalTime;

/** Sky colour presets and a client-side time of day. Purely visual. */
public final class CustomSkyModule extends ClientModule {
	private final Setting preset = add(Setting.mode("Sky", "Synthwave",
			"Default", "Sunset", "Aurora", "Synthwave", "Ocean", "Rose", "Custom"));
	private final Setting customHue = add(Setting.hue("Custom Hue", 0.6));
	private final Setting strength = add(Setting.number("Strength", 0.9, 0.0, 1.0, 0.1));
	private final Setting time = add(Setting.mode("Time", "Server",
			"Server", "Sunrise", "Day", "Noon", "Sunset", "Night", "Midnight", "Real Clock"));

	private boolean overriding;
	private long baseTimeOfDay;
	private long baseWorldTime;

	public CustomSkyModule() {
		super("Custom Sky", "Recolour the sky and pick your own time of day (client-side only).", Category.VISUALS);
		status = "Only you see this. The server's time is not changed.";
	}

	@Override
	public void onTick(MinecraftClient mc) {
		ClientWorld world = mc.world;
		if (world == null) {
			overriding = false;
			return;
		}
		String mode = time.getMode();
		if (mode.equals("Server")) {
			restore(world);
			return;
		}
		if (!overriding) {
			overriding = true;
			baseTimeOfDay = world.getTimeOfDay();
			baseWorldTime = world.getTime();
		}
		world.setTime(world.getTime(), ticksFor(mode), false);
	}

	@Override
	protected void onDisable() {
		ClientWorld world = MinecraftClient.getInstance().world;
		if (world != null) restore(world);
		overriding = false;
	}

	private void restore(ClientWorld world) {
		if (!overriding) return;
		overriding = false;
		long elapsed = world.getTime() - baseWorldTime;
		world.setTime(world.getTime(), baseTimeOfDay + Math.max(0, elapsed), true);
	}

	private static long ticksFor(String mode) {
		return switch (mode) {
			case "Sunrise" -> 23000;
			case "Day" -> 1000;
			case "Noon" -> 6000;
			case "Sunset" -> 12000;
			case "Night" -> 14000;
			case "Midnight" -> 18000;
			default -> {
				// Real clock: 06:00 is tick 0, so noon is 6000 and midnight is 18000.
				LocalTime now = LocalTime.now();
				double hours = now.getHour() + now.getMinute() / 60.0 + now.getSecond() / 3600.0;
				yield (long) (((hours - 6.0 + 24.0) % 24.0) * 1000.0);
			}
		};
	}

	private int presetColor() {
		return switch (preset.getMode()) {
			case "Sunset" -> ColorUtil.hsb(0.04f, 0.75f, 1f);
			case "Aurora" -> ColorUtil.hsb(0.45f, 0.7f, 0.95f);
			case "Synthwave" -> ColorUtil.hsb(0.80f, 0.8f, 1f);
			case "Ocean" -> ColorUtil.hsb(0.54f, 0.75f, 1f);
			case "Rose" -> ColorUtil.hsb(0.95f, 0.45f, 1f);
			case "Custom" -> ColorUtil.hsb((float) customHue.getHue(), 0.7f, 1f);
			default -> -1;
		};
	}

	/** Tints the vanilla sky colour while keeping its day/night brightness. */
	public int tintSky(int argb) {
		int target = presetColor();
		if (target < 0) return argb;
		int base = argb & 0xFFFFFF;
		float luminance = Math.max(((base >> 16) & 255), Math.max((base >> 8) & 255, base & 255)) / 255f;
		int tinted = ColorUtil.scale(target, luminance);
		int rgb = ColorUtil.mixRgb(base, tinted, (float) strength.getNumber());
		return (argb & 0xFF000000) | rgb;
	}
}
