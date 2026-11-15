package dev.lumen.module.impl;

import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.ui.RenderUtil;
import dev.lumen.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** On-screen WASD / mouse / space display with CPS. Display only, it does not change any input. */
public final class KeystrokesModule extends ClientModule {
	private static final int K = 22;
	private static final int G = 3;
	private static final int W = 3 * K + 2 * G;

	private final Setting mouse = add(Setting.bool("Mouse Buttons", true));
	private final Setting cps = add(Setting.bool("Show CPS", true));
	private final Setting space = add(Setting.bool("Spacebar", true));
	private final Setting position = add(Setting.mode("Position", "Bottom Right",
			"Bottom Right", "Bottom Left", "Top Left", "Top Right"));
	private final Setting scale = add(Setting.number("Scale", 1.0, 0.7, 1.6, 0.1));

	private final Deque<Long> leftClicks = new ArrayDeque<>();
	private final Deque<Long> rightClicks = new ArrayDeque<>();
	private final float[] anim = new float[7];
	private long lastNanos;

	public KeystrokesModule() {
		super("Keystrokes", "Shows your pressed keys and clicks per second on screen.", Category.UTILITY);
	}

	/** Called from the mouse mixin for every real button press (accurate CPS even for very fast clicks). */
	public void registerClick(int button) {
		if (MinecraftClient.getInstance().currentScreen != null) return;
		long now = System.currentTimeMillis();
		if (button == 0) leftClicks.addLast(now);
		else if (button == 1) rightClicks.addLast(now);
	}

	private static int count(Deque<Long> clicks) {
		long cutoff = System.currentTimeMillis() - 1000;
		while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
		return clicks.size();
	}

	@Override
	protected void onDisable() {
		leftClicks.clear();
		rightClicks.clear();
	}

	@Override
	public void onHud(DrawContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		GameOptions o = mc.options;
		KeyBinding[] keys = { o.forwardKey, o.leftKey, o.backKey, o.rightKey, o.attackKey, o.useKey, o.jumpKey };

		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		for (int i = 0; i < anim.length; i++) {
			float target = keys[i].isPressed() ? 1f : 0f;
			anim[i] += (target - anim[i]) * Math.min(1f, dt * 20f);
		}

		boolean showMouse = mouse.getBool();
		boolean showSpace = space.getBool();
		int h = K + G + K;
		if (showMouse) h += G + K;
		if (showSpace) h += G + 14;

		float s = (float) scale.getNumber();
		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();
		float x, y;
		switch (position.getMode()) {
			case "Bottom Left" -> { x = 6; y = sh - h * s - 30; }
			case "Top Left" -> { x = 6; y = 28; }
			case "Top Right" -> { x = sw - W * s - 6; y = 6 + 52 * s; }
			default -> { x = sw - W * s - 6; y = sh - h * s - 6; }
		}

		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(s, s, 1f);

		int cy = 0;
		drawKey(ctx, K + G, cy, K, K, label(keys[0]), anim[0], null);
		cy += K + G;
		drawKey(ctx, 0, cy, K, K, label(keys[1]), anim[1], null);
		drawKey(ctx, K + G, cy, K, K, label(keys[2]), anim[2], null);
		drawKey(ctx, 2 * (K + G), cy, K, K, label(keys[3]), anim[3], null);
		cy += K + G;

		if (showMouse) {
			int lw = (W - G) / 2;
			int rw = W - G - lw;
			drawKey(ctx, 0, cy, lw, K, "LMB", anim[4], cps.getBool() ? count(leftClicks) + " CPS" : null);
			drawKey(ctx, lw + G, cy, rw, K, "RMB", anim[5], cps.getBool() ? count(rightClicks) + " CPS" : null);
			cy += K + G;
		}
		if (showSpace) {
			float a = anim[6];
			RenderUtil.roundRect(ctx, 0, cy, W, 14, 5, RenderUtil.lerpArgb(0xB00B0C11, RenderUtil.alpha(Theme.accent(), 0.9f), a));
			RenderUtil.roundRect(ctx, W / 2 - 12, cy + 6, 24, 2, 1, RenderUtil.lerpArgb(Theme.MUTED, 0xFF0B0C11, a));
		}

		ctx.getMatrices().pop();
	}

	private static String label(KeyBinding key) {
		String s = key.getBoundKeyLocalizedText().getString().toUpperCase(Locale.ROOT);
		return s.length() > 3 ? s.substring(0, 3) : s;
	}

	private static void drawKey(DrawContext ctx, int x, int y, int w, int h, String label, float a, String sub) {
		RenderUtil.roundRect(ctx, x, y, w, h, 5, RenderUtil.lerpArgb(0xB00B0C11, RenderUtil.alpha(Theme.accent(), 0.9f), a));
		int text = RenderUtil.lerpArgb(Theme.TEXT, 0xFF0B0C11, a);
		if (sub == null) {
			RenderUtil.centered(ctx, label, x + w / 2f, y + (h - 8) / 2f, 1f, text, false);
		} else {
			RenderUtil.centered(ctx, label, x + w / 2f, y + 3, 0.9f, text, false);
			RenderUtil.centered(ctx, sub, x + w / 2f, y + 13, 0.7f, RenderUtil.lerpArgb(Theme.MUTED, 0xFF2A2E3F, a), false);
		}
	}
}
