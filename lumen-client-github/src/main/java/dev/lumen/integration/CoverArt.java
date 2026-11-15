package dev.lumen.integration;

import dev.lumen.LumenClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns image bytes (album art, browser thumbnail) into a Minecraft texture plus a matching accent colour.
 * Decoding happens on a background thread; the texture is created on the render thread.
 */
public final class CoverArt {
	public static final int DEFAULT_TINT = 0x1DB954;

	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Lumen-Cover");
		t.setDaemon(true);
		return t;
	});

	private final String name;
	private volatile Identifier id;
	private volatile int tint = DEFAULT_TINT;
	private volatile long generation;
	private int counter;

	/** @param name lowercase letters only, used in the texture path */
	public CoverArt(String name) {
		this.name = name;
	}

	public Identifier id() { return id; }

	public int tint() { return tint; }

	public void clear() {
		generation++;
		Identifier old = id;
		id = null;
		tint = DEFAULT_TINT;
		if (old != null) {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> mc.getTextureManager().destroyTexture(old));
		}
	}

	public void set(byte[] bytes) {
		long gen = ++generation;
		POOL.execute(() -> {
			try {
				BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
				if (src == null) return;

				// centre-crop to a square (browser thumbnails are usually 16:9)
				int side = Math.min(src.getWidth(), src.getHeight());
				BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);

				int newTint = dominantColor(square);

				BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(square, 0, 0, 128, 128, null);
				g.dispose();

				ByteArrayOutputStream png = new ByteArrayOutputStream();
				ImageIO.write(out, "png", png);
				NativeImage image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));

				MinecraftClient.getInstance().execute(() -> install(image, newTint, gen));
			} catch (Exception e) {
				LumenClient.LOG.debug("Cover decode failed: {}", e.toString());
			}
		});
	}

	private void install(NativeImage image, int newTint, long gen) {
		if (gen != generation) {
			image.close();
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		Identifier old = id;
		Identifier fresh = Identifier.of("lumen", "cover/" + name + "/" + (++counter));
		mc.getTextureManager().registerTexture(fresh, new NativeImageBackedTexture(image));
		id = fresh;
		tint = newTint;
		if (old != null) mc.getTextureManager().destroyTexture(old);
	}

	/** Saturation-weighted average colour, nudged so it always works as a bright accent on a dark card. */
	private static int dominantColor(BufferedImage src) {
		BufferedImage small = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = small.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, 24, 24, null);
		g.dispose();
		double r = 0, gr = 0, b = 0, total = 0;
		float[] hsb = new float[3];
		for (int y = 0; y < 24; y++) {
			for (int x = 0; x < 24; x++) {
				int rgb = small.getRGB(x, y);
				int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
				Color.RGBtoHSB(cr, cg, cb, hsb);
				double w = hsb[1] * hsb[2] + 0.05;
				r += cr * w;
				gr += cg * w;
				b += cb * w;
				total += w;
			}
		}
		if (total <= 0) return DEFAULT_TINT;
		Color.RGBtoHSB((int) (r / total), (int) (gr / total), (int) (b / total), hsb);
		return Color.HSBtoRGB(hsb[0], Math.max(0.45f, hsb[1]), Math.max(0.75f, hsb[2])) & 0xFFFFFF;
	}
}
