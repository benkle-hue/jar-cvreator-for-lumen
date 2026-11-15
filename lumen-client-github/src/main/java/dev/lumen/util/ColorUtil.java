package dev.lumen.util;

import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.function.IntUnaryOperator;

public final class ColorUtil {
	private ColorUtil() {}

	/**
	 * Applies an RGB transform to a value that Minecraft returns either as ARGB int (1.21.2+)
	 * or as a Vec3d (older versions). This keeps the sky/cloud mixins tolerant.
	 */
	public static Object remap(Object original, IntUnaryOperator fn) {
		if (original instanceof Integer i) {
			int rgb = fn.applyAsInt(i & 0xFFFFFF) & 0xFFFFFF;
			return (i & 0xFF000000) | rgb;
		}
		if (original instanceof Vec3d v) {
			int r = clamp((int) Math.round(v.x * 255.0));
			int g = clamp((int) Math.round(v.y * 255.0));
			int b = clamp((int) Math.round(v.z * 255.0));
			int out = fn.applyAsInt((r << 16) | (g << 8) | b);
			return new Vec3d(((out >> 16) & 255) / 255.0, ((out >> 8) & 255) / 255.0, (out & 255) / 255.0);
		}
		return original;
	}

	public static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

	public static int mixRgb(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
	}

	public static int scale(int rgb, float f) {
		int r = clamp((int) (((rgb >> 16) & 255) * f));
		int g = clamp((int) (((rgb >> 8) & 255) * f));
		int b = clamp((int) ((rgb & 255) * f));
		return (r << 16) | (g << 8) | b;
	}

	public static int hsb(float h, float s, float b) {
		return Color.HSBtoRGB(h, s, b) & 0xFFFFFF;
	}

	public static float rainbowHue(double seconds) {
		return (float) ((System.currentTimeMillis() % (long) (seconds * 1000)) / (seconds * 1000.0));
	}
}
