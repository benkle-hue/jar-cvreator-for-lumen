package dev.lumen.ui;

import dev.lumen.module.ModuleManager;
import dev.lumen.module.impl.InterfaceModule;
import dev.lumen.util.ColorUtil;

/** Shared colours. Accent follows the Interface module (fixed hue or animated rainbow). */
public final class Theme {
	private Theme() {}

	public static final int BG = 0xF20B0C11;
	public static final int SIDEBAR = 0xFF0F1117;
	public static final int PANEL = 0xFF141720;
	public static final int CARD = 0xFF1A1D28;
	public static final int CARD_HOVER = 0xFF202432;
	public static final int CARD_OPEN = 0xFF171A24;
	public static final int BORDER = 0xFF262A3A;
	public static final int TEXT = 0xFFEDEFF7;
	public static final int MUTED = 0xFF8A8FA6;
	public static final int FAINT = 0xFF565B70;
	public static final int OFF_TRACK = 0xFF2A2E3F;

	public static int accent() {
		InterfaceModule ui = ModuleManager.get(InterfaceModule.class);
		float hue = ui == null ? 0.72f : ui.hue();
		return 0xFF000000 | ColorUtil.hsb(hue, 0.55f, 1f);
	}

	/** Second gradient colour, shifted along the hue wheel. */
	public static int accent2() {
		InterfaceModule ui = ModuleManager.get(InterfaceModule.class);
		float hue = ui == null ? 0.72f : ui.hue();
		return 0xFF000000 | ColorUtil.hsb((hue + 0.09f) % 1f, 0.6f, 1f);
	}

	public static int accentDim() { return RenderUtil.alpha(accent(), 0.22f); }
}
