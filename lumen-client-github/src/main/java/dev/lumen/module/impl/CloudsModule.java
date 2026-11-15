package dev.lumen.module.impl;

import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.util.ColorUtil;

/** Cloud height and colour. */
public final class CloudsModule extends ClientModule {
	private final Setting height = add(Setting.number("Height", 192, 64, 320, 4));
	private final Setting colorMode = add(Setting.mode("Colour", "Tint", "Default", "Tint", "Rainbow"));
	private final Setting hue = add(Setting.hue("Tint Hue", 0.92));
	private final Setting brightness = add(Setting.number("Brightness", 1.0, 0.5, 1.5, 0.1));

	public CloudsModule() {
		super("Clouds", "Change cloud height and colour. Needs clouds enabled in Video Settings.", Category.VISUALS);
	}

	public float height() { return (float) height.getNumber(); }

	/** Keeps the original brightness curve so clouds still darken at night. */
	public int tintClouds(int argb) {
		int base = argb & 0xFFFFFF;
		float luminance = Math.max(((base >> 16) & 255), Math.max((base >> 8) & 255, base & 255)) / 255f;
		int rgb = base;
		switch (colorMode.getMode()) {
			case "Tint" -> rgb = ColorUtil.scale(ColorUtil.hsb((float) hue.getHue(), 0.5f, 1f), luminance);
			case "Rainbow" -> rgb = ColorUtil.scale(ColorUtil.hsb(ColorUtil.rainbowHue(14), 0.45f, 1f), luminance);
			default -> { }
		}
		rgb = ColorUtil.scale(rgb, (float) brightness.getNumber());
		return (argb & 0xFF000000) | (rgb & 0xFFFFFF);
	}
}
