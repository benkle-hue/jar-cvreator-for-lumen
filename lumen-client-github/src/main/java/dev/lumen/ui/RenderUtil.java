package dev.lumen.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Small 2D drawing helpers: anti-aliased rounded rectangles, scaled text, gradients. */
public final class RenderUtil {
	private RenderUtil() {}

	public static void roundRect(DrawContext ctx, float fx, float fy, float fw, float fh, int radius, int argb) {
		int x = Math.round(fx), y = Math.round(fy), w = Math.round(fw), h = Math.round(fh);
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		int r = Math.min(radius, Math.min(w, h) / 2);
		if (r <= 0) {
			ctx.fill(x, y, x + w, y + h, argb);
			return;
		}
		int alpha = argb >>> 24;
		int rgb = argb & 0xFFFFFF;

		ctx.fill(x, y + r, x + w, y + h - r, argb);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			double inset = r - Math.sqrt(r * (double) r - dy * dy);
			int full = (int) Math.ceil(inset);
			double coverage = full - inset;

			ctx.fill(x + full, y + i, x + w - full, y + i + 1, argb);
			ctx.fill(x + full, y + h - 1 - i, x + w - full, y + h - i, argb);

			if (full > 0 && coverage > 0.04) {
				int edge = ((int) Math.round(alpha * coverage) << 24) | rgb;
				ctx.fill(x + full - 1, y + i, x + full, y + i + 1, edge);
				ctx.fill(x + w - full, y + i, x + w - full + 1, y + i + 1, edge);
				ctx.fill(x + full - 1, y + h - 1 - i, x + full, y + h - i, edge);
				ctx.fill(x + w - full, y + h - 1 - i, x + w - full + 1, y + h - i, edge);
			}
		}
	}

	/** 1px outline by drawing the border colour first and the fill slightly smaller on top. */
	public static void roundRectBordered(DrawContext ctx, int x, int y, int w, int h, int radius, int fill, int border) {
		roundRect(ctx, x, y, w, h, radius, border);
		roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
	}

	public static void gradientV(DrawContext ctx, int x, int y, int w, int h, int top, int bottom) {
		ctx.fillGradient(x, y, x + w, y + h, top, bottom);
	}

	/** Horizontal two-colour gradient made of 1px columns (cheap enough for small bars). */
	public static void gradientH(DrawContext ctx, int x, int y, int w, int h, int left, int right) {
		for (int i = 0; i < w; i++) {
			float t = w <= 1 ? 0 : i / (float) (w - 1);
			ctx.fill(x + i, y, x + i + 1, y + h, lerpArgb(left, right, t));
		}
	}

	public static void text(DrawContext ctx, String s, float x, float y, float scale, int argb, boolean shadow) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		if (scale == 1f) {
			ctx.drawText(tr, s, Math.round(x), Math.round(y), argb, shadow);
			return;
		}
		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(scale, scale, 1f);
		ctx.drawText(tr, s, 0, 0, argb, shadow);
		ctx.getMatrices().pop();
	}

	public static void centered(DrawContext ctx, String s, float cx, float y, float scale, int argb, boolean shadow) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		text(ctx, s, cx - tr.getWidth(s) * scale / 2f, y, scale, argb, shadow);
	}

	public static int width(String s, float scale) {
		return Math.round(MinecraftClient.getInstance().textRenderer.getWidth(s) * scale);
	}

	public static String trim(String s, int maxWidth) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		if (tr.getWidth(s) <= maxWidth) return s;
		return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth("..."))) + "...";
	}

	public static int alpha(int argb, float factor) {
		int a = Math.max(0, Math.min(255, Math.round((argb >>> 24) * factor)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int lerpArgb(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int aa = Math.round((a >>> 24) * (1 - t) + (b >>> 24) * t);
		int ar = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int ag = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int ab = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (aa << 24) | (ar << 16) | (ag << 8) | ab;
	}

	public static String time(long ms) {
		long s = Math.max(0, ms / 1000);
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}
}
