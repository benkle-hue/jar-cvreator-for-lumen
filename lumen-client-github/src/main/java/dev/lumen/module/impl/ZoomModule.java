package dev.lumen.module.impl;

import dev.lumen.LumenClient;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import net.minecraft.client.MinecraftClient;

/** Hold a key to zoom in (like the spyglass, but anywhere). Scroll while zooming changes the level. */
public final class ZoomModule extends ClientModule {
	private final Setting level = add(Setting.number("Zoom Level", 4, 1.5, 10, 0.5));
	private final Setting smooth = add(Setting.bool("Smooth Zoom", true));
	private final Setting scroll = add(Setting.bool("Scroll To Adjust", true));
	private final Setting slowMouse = add(Setting.bool("Lower Sensitivity", true));
	private final Setting cinematic = add(Setting.bool("Cinematic Camera", false));

	private boolean zooming;
	private double factor = 1.0;
	private long lastNanos;

	private boolean savedCinematic;
	private double savedSensitivity = -1;

	public ZoomModule() {
		super("Zoom", "Hold the zoom key (default C) to zoom in. Scroll to adjust.", Category.VISUALS);
		status = "Rebind the key in Controls > Lumen Client.";
	}

	@Override
	public void onTick(MinecraftClient mc) {
		boolean want = mc.currentScreen == null && LumenClient.zoom != null && LumenClient.zoom.isPressed();
		if (want == zooming) return;
		zooming = want;
		if (zooming) beginZoom(mc); else endZoom(mc);
	}

	private void beginZoom(MinecraftClient mc) {
		if (slowMouse.getBool()) {
			savedSensitivity = mc.options.getMouseSensitivity().getValue();
			mc.options.getMouseSensitivity().setValue(Math.max(0.01, savedSensitivity / Math.max(1.0, level.getNumber())));
		}
		if (cinematic.getBool()) {
			savedCinematic = mc.options.smoothCameraEnabled;
			mc.options.smoothCameraEnabled = true;
		}
	}

	private void endZoom(MinecraftClient mc) {
		if (savedSensitivity >= 0) {
			mc.options.getMouseSensitivity().setValue(savedSensitivity);
			savedSensitivity = -1;
		}
		if (cinematic.getBool()) mc.options.smoothCameraEnabled = savedCinematic;
	}

	@Override
	protected void onDisable() {
		if (zooming) endZoom(MinecraftClient.getInstance());
		zooming = false;
		factor = 1.0;
	}

	/** Called from the FOV mixin on the render thread. */
	public float modifyFov(float fov) {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1_000_000_000.0);
		lastNanos = now;

		double target = zooming ? 1.0 / level.getNumber() : 1.0;
		if (smooth.getBool()) {
			factor += (target - factor) * (1.0 - Math.exp(-dt * 14.0));
			if (Math.abs(target - factor) < 0.0005) factor = target;
		} else {
			factor = target;
		}
		return factor == 1.0 ? fov : (float) (fov * factor);
	}

	/** @return true if the scroll event was consumed. */
	public boolean handleScroll(double vertical) {
		if (!zooming || !scroll.getBool() || vertical == 0) return false;
		level.setNumber(level.getNumber() + Math.signum(vertical) * 0.5);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (savedSensitivity >= 0) {
			mc.options.getMouseSensitivity().setValue(Math.max(0.01, savedSensitivity / Math.max(1.0, level.getNumber())));
		}
		return true;
	}
}
