package dev.lumen.module.impl;

import dev.lumen.LumenClient;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.ui.RenderUtil;
import dev.lumen.ui.Theme;
import dev.lumen.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** Theme + HUD watermark. Always on; its settings control how the whole client looks. */
public final class InterfaceModule extends ClientModule {
	private final Setting accent = add(Setting.hue("Accent", 0.74));
	private final Setting rainbow = add(Setting.bool("Rainbow Accent", false));
	private final Setting dim = add(Setting.bool("Dim Background", true));
	private final Setting watermark = add(Setting.bool("Watermark", true));
	private final Setting fps = add(Setting.bool("Show FPS", true));

	public InterfaceModule() {
		super("Interface", "Menu theme, accent colour and HUD watermark.", Category.CLIENT);
		alwaysOn = true;
	}

	public float hue() {
		return rainbow.getBool() ? ColorUtil.rainbowHue(10) : (float) accent.getHue();
	}

	public boolean dimBackground() { return dim.getBool(); }

	@Override
	public void onHud(DrawContext ctx) {
		if (!watermark.getBool()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getDebugHud().shouldShowDebugHud()) return;

		String brand = "LUMEN";
		String tail = fps.getBool() ? mc.getCurrentFps() + " FPS" : "v" + LumenClient.VERSION;
		int brandW = RenderUtil.width(brand, 1f);
		int tailW = RenderUtil.width(tail, 1f);
		int w = 8 + brandW + 9 + tailW + 8;
		int h = 16;
		int x = 6, y = 6;

		RenderUtil.roundRect(ctx, x, y, w, h, 6, 0xCC0B0C11);
		RenderUtil.roundRect(ctx, x + 4, y + 5, 2, 6, 1, Theme.accent());
		RenderUtil.text(ctx, brand, x + 9, y + 4, 1f, Theme.accent(), false);
		RenderUtil.text(ctx, tail, x + 9 + brandW + 7, y + 4, 1f, Theme.MUTED, false);
	}
}
