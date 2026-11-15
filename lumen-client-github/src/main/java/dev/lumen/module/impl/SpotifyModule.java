package dev.lumen.module.impl;

import dev.lumen.integration.SpotifyService;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.ui.RenderUtil;
import dev.lumen.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Now-playing HUD plus playback controls (needs Spotify Premium for the controls). */
public final class SpotifyModule extends ClientModule {
	private static final int HUD_W = 164;
	private static final int HUD_H = 46;

	private final Setting clientId = add(Setting.text("Client ID", "", "Paste your Spotify app Client ID"));
	private final Setting hud = add(Setting.bool("Show HUD", true));
	private final Setting position = add(Setting.mode("HUD Position", "Top Right",
			"Top Right", "Top Left", "Bottom Right", "Bottom Left"));
	private final Setting scale = add(Setting.number("HUD Scale", 1.0, 0.7, 1.6, 0.1));
	private final Setting coverColors = add(Setting.bool("Cover Colours", true));
	private final Setting hideWhenPaused = add(Setting.bool("Hide When Paused", false));

	public SpotifyModule() {
		super("Spotify", "Now-playing card with cover art and playback controls.", Category.INTEGRATIONS);
		addActionRow(new Action(() -> switch (SpotifyService.state()) {
			case CONNECTED -> "Disconnect";
			case CONNECTING -> "Connecting...";
			default -> "Connect";
		}, this::toggleConnection));
		addActionRow(
				Action.of("Prev", SpotifyService::previous),
				new Action(() -> {
					SpotifyService.Track t = SpotifyService.current();
					return t != null && t.playing() ? "Pause" : "Play";
				}, SpotifyService::playPause),
				Action.of("Next", SpotifyService::next));
		refreshStatus();
	}

	private void toggleConnection() {
		switch (SpotifyService.state()) {
			case CONNECTED -> SpotifyService.disconnect();
			case DISCONNECTED -> {
				if (!isEnabled()) setEnabled(true);
				SpotifyService.connect(clientId.getText());
			}
			default -> { }
		}
		refreshStatus();
	}

	@Override
	protected void onEnable() {
		SpotifyService.start(clientId.getText());
	}

	@Override
	protected void onDisable() {
		SpotifyService.stop();
	}

	@Override
	public void onTick(MinecraftClient mc) {
		refreshStatus();
	}

	private void refreshStatus() {
		String msg = SpotifyService.message();
		status = switch (SpotifyService.state()) {
			case CONNECTED -> !msg.isEmpty() ? msg
					: SpotifyService.deviceName().isEmpty() ? "Connected" : "Connected - " + SpotifyService.deviceName();
			case CONNECTING -> msg.isEmpty() ? "Connecting..." : msg;
			default -> msg.isEmpty() ? "Paste your Client ID, then press Connect." : msg;
		};
	}

	// ---------------------------------------------------------------- settings panel card

	@Override
	public int panelExtraHeight() { return 62; }

	@Override
	public void renderPanelExtra(DrawContext ctx, int x, int y, int w) {
		SpotifyService.Track t = SpotifyService.current();
		int tint = t != null && coverColors.getBool() ? 0xFF000000 | t.tint() : Theme.accent();
		int bg = RenderUtil.lerpArgb(Theme.CARD, tint | 0xFF000000, 0.12f);
		RenderUtil.roundRect(ctx, x, y, w, 58, 8, bg);

		drawCover(ctx, t, x + 8, y + 8, 42);

		int tx = x + 58;
		int tw = w - 66;
		if (t == null) {
			RenderUtil.text(ctx, "Nothing playing", tx, y + 14, 1f, Theme.TEXT, false);
			RenderUtil.text(ctx, RenderUtil.trim("Start Spotify on any device", tw), tx, y + 27, 0.85f, Theme.MUTED, false);
			return;
		}
		RenderUtil.text(ctx, RenderUtil.trim(t.title(), tw), tx, y + 9, 1f, Theme.TEXT, false);
		RenderUtil.text(ctx, RenderUtil.trim(t.artist(), tw), tx, y + 21, 0.85f, Theme.MUTED, false);

		int by = y + 38;
		float p = t.durationMs() > 0 ? Math.min(1f, t.progressMs() / (float) t.durationMs()) : 0f;
		RenderUtil.roundRect(ctx, tx, by, tw, 3, 1, Theme.OFF_TRACK);
		RenderUtil.roundRect(ctx, tx, by, Math.max(2, Math.round(tw * p)), 3, 1, t.playing() ? tint : Theme.FAINT);
		RenderUtil.text(ctx, RenderUtil.time(t.progressMs()), tx, by + 6, 0.7f, Theme.FAINT, false);
		String total = RenderUtil.time(t.durationMs());
		RenderUtil.text(ctx, total, tx + tw - RenderUtil.width(total, 0.7f), by + 6, 0.7f, Theme.FAINT, false);
	}

	private void drawCover(DrawContext ctx, SpotifyService.Track t, int x, int y, int size) {
		if (t != null && t.cover() != null) {
			ctx.drawTexture(RenderLayer::getGuiTextured, t.cover(), x, y, 0f, 0f, size, size, size, size);
		} else {
			RenderUtil.roundRect(ctx, x, y, size, size, 6, Theme.OFF_TRACK);
			RenderUtil.centered(ctx, "~", x + size / 2f, y + size / 2f - 4, 1f, Theme.FAINT, false);
		}
	}

	// ---------------------------------------------------------------- in-game HUD

	@Override
	public void onHud(DrawContext ctx) {
		if (!hud.getBool()) return;
		SpotifyService.Track t = SpotifyService.current();
		if (t == null || (hideWhenPaused.getBool() && !t.playing())) return;

		float s = (float) scale.getNumber();
		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();
		int margin = 6;
		float w = HUD_W * s, h = HUD_H * s;
		float x, y;
		switch (position.getMode()) {
			case "Top Left" -> { x = margin; y = 28; }
			case "Bottom Left" -> { x = margin; y = sh - h - 28; }
			case "Bottom Right" -> { x = sw - w - margin; y = sh - h - margin; }
			default -> { x = sw - w - margin; y = margin; }
		}

		int tint = coverColors.getBool() ? 0xFF000000 | t.tint() : Theme.accent();
		float alpha = t.playing() ? 1f : 0.7f;

		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(s, s, 1f);

		RenderUtil.roundRect(ctx, -1, -1, HUD_W + 2, HUD_H + 2, 9, RenderUtil.alpha(tint, 0.35f * alpha));
		RenderUtil.roundRect(ctx, 0, 0, HUD_W, HUD_H, 8, 0xE00B0C11);
		RenderUtil.roundRect(ctx, 0, 0, HUD_W, HUD_H, 8, RenderUtil.alpha(tint, 0.10f * alpha));

		drawCover(ctx, t, 6, 6, 34);

		int tx = 47;
		int tw = HUD_W - tx - 8;
		RenderUtil.text(ctx, RenderUtil.trim(t.title(), tw), tx, 7, 1f, Theme.TEXT, false);
		RenderUtil.text(ctx, RenderUtil.trim(t.artist(), tw), tx, 19, 0.85f, Theme.MUTED, false);

		float p = t.durationMs() > 0 ? Math.min(1f, t.progressMs() / (float) t.durationMs()) : 0f;
		RenderUtil.roundRect(ctx, tx, HUD_H - 12, tw, 3, 1, 0xFF2A2E3F);
		RenderUtil.roundRect(ctx, tx, HUD_H - 12, Math.max(2, Math.round(tw * p)), 3, 1, RenderUtil.alpha(tint, alpha));
		String left = RenderUtil.time(t.progressMs());
		String right = RenderUtil.time(t.durationMs());
		RenderUtil.text(ctx, left, tx, HUD_H - 8, 0.6f, Theme.FAINT, false);
		RenderUtil.text(ctx, right, tx + tw - RenderUtil.width(right, 0.6f), HUD_H - 8, 0.6f, Theme.FAINT, false);

		ctx.getMatrices().pop();
	}
}
