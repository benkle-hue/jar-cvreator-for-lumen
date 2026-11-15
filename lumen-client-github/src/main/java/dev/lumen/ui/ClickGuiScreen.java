package dev.lumen.ui;

import dev.lumen.LumenClient;
import dev.lumen.config.Config;
import dev.lumen.module.ClientModule;
import dev.lumen.module.ClientModule.Action;
import dev.lumen.module.ClientModule.Category;
import dev.lumen.module.ModuleManager;
import dev.lumen.module.Setting;
import dev.lumen.module.impl.InterfaceModule;
import dev.lumen.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Lumen menu: sidebar with categories, animated module cards, expandable settings.
 * Immediate-mode: every frame registers "hit" rectangles that the mouse handlers use.
 */
public final class ClickGuiScreen extends Screen {
	private static final int SIDEBAR = 108;
	private static final int CARD_H = 34;
	private static Category lastCategory = Category.VISUALS;

	private record Hit(int x, int y, int w, int h, boolean content,
					   BiConsumer<Double, Integer> click, Consumer<Double> drag) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<ClientModule, Float> openAnim = new HashMap<>();
	private final Map<Setting, Float> boolAnim = new HashMap<>();
	private final Set<ClientModule> open = new HashSet<>();

	private Category selected = lastCategory;
	private Setting editing;
	private Hit activeDrag;

	private float intro;
	private float scroll;
	private float scrollTarget;
	private float contentHeight;
	private long lastNanos;
	private float dt = 0.016f;

	// window geometry (recomputed every frame)
	private int wx, wy, ww, wh;
	private int contentTop, contentBottom;

	public ClickGuiScreen() {
		super(Text.literal("Lumen"));
	}

	@Override
	public boolean shouldPause() { return false; }

	@Override
	public void removed() {
		editing = null;
		lastCategory = selected;
		Config.save();
		for (ClientModule m : ModuleManager.all()) m.onGuiClose();
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void render(DrawContext ctx, int mx, int my, float delta) {
		long now = System.nanoTime();
		dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		intro = Math.min(1f, intro + dt * 6f);
		float ease = 1f - (float) Math.pow(1f - intro, 3);
		hits.clear();

		ww = Math.min(580, width - 24);
		wh = Math.min(340, height - 24);
		wx = (width - ww) / 2;
		wy = (height - wh) / 2;
		contentTop = wy + 48;
		contentBottom = wy + wh - 10;

		InterfaceModule ui = ModuleManager.get(InterfaceModule.class);
		if (ui == null || ui.dimBackground()) {
			ctx.fillGradient(0, 0, width, height, RenderUtil.alpha(0xFF05060A, 0.55f * ease), RenderUtil.alpha(0xFF0A0614, 0.78f * ease));
		}

		for (ClientModule m : ModuleManager.all()) {
			m.toggleAnim += ((m.isEnabled() ? 1f : 0f) - m.toggleAnim) * Math.min(1f, dt * 14f);
		}

		ctx.getMatrices().push();
		float sc = 0.95f + 0.05f * ease;
		ctx.getMatrices().translate(width / 2f, height / 2f, 0);
		ctx.getMatrices().scale(sc, sc, 1f);
		ctx.getMatrices().translate(-width / 2f, -height / 2f, 0);

		drawWindow(ctx);
		drawSidebar(ctx, mx, my);
		drawContent(ctx, mx, my);

		ctx.getMatrices().pop();
	}

	private void drawWindow(DrawContext ctx) {
		RenderUtil.roundRect(ctx, wx - 1, wy - 1, ww + 2, wh + 2, 13, RenderUtil.alpha(Theme.accent(), 0.28f));
		RenderUtil.roundRect(ctx, wx, wy, ww, wh, 12, Theme.BG);
		RenderUtil.gradientH(ctx, wx + 14, wy + 1, ww - 28, 1, RenderUtil.alpha(Theme.accent(), 0f), RenderUtil.alpha(Theme.accent(), 0.9f));
		RenderUtil.gradientH(ctx, wx + ww / 2, wy + 1, ww / 2 - 14, 1, RenderUtil.alpha(Theme.accent(), 0.9f), RenderUtil.alpha(Theme.accent2(), 0f));
	}

	private void drawSidebar(DrawContext ctx, int mx, int my) {
		int sx = wx + 6, sy = wy + 6, sw = SIDEBAR - 6, sh = wh - 12;
		RenderUtil.roundRect(ctx, sx, sy, sw, sh, 9, Theme.SIDEBAR);

		RenderUtil.text(ctx, "LUMEN", sx + 12, sy + 11, 1.35f, Theme.accent(), false);
		RenderUtil.text(ctx, "CLIENT", sx + 13, sy + 25, 0.7f, Theme.FAINT, false);

		int y = sy + 46;
		for (Category c : Category.values()) {
			int x = sx + 6, w = sw - 12, h = 24;
			boolean sel = c == selected;
			boolean hov = !sel && mx >= x && mx < x + w && my >= y && my < y + h;

			if (sel) {
				RenderUtil.roundRect(ctx, x, y, w, h, 7, Theme.accentDim());
				RenderUtil.roundRect(ctx, x, y + 6, 2, h - 12, 1, Theme.accent());
			} else if (hov) {
				RenderUtil.roundRect(ctx, x, y, w, h, 7, Theme.CARD);
			}
			RenderUtil.text(ctx, c.label, x + 10, y + 8, 1f, sel ? Theme.TEXT : Theme.MUTED, false);

			int on = 0;
			for (ClientModule m : ModuleManager.in(c)) {
				if (m.isEnabled() && !m.alwaysOn) on++;
			}
			if (on > 0) {
				String n = String.valueOf(on);
				int bw = RenderUtil.width(n, 0.8f) + 8;
				RenderUtil.roundRect(ctx, x + w - bw - 5, y + 6, bw, 12, 6, sel ? Theme.accent() : Theme.OFF_TRACK);
				RenderUtil.text(ctx, n, x + w - bw - 1, y + 8, 0.8f, sel ? 0xFF0B0C11 : Theme.MUTED, false);
			}

			hits.add(new Hit(x, y, w, h, false, (px, b) -> {
				if (selected != c) {
					selected = c;
					scroll = 0;
					scrollTarget = 0;
					editing = null;
				}
			}, null));
			y += 28;
		}

		String key = LumenClient.openGui == null ? "RSHIFT" : LumenClient.openGui.getBoundKeyLocalizedText().getString();
		RenderUtil.text(ctx, key + " to close", sx + 12, sy + sh - 22, 0.7f, Theme.FAINT, false);
		RenderUtil.text(ctx, "v" + LumenClient.VERSION, sx + 12, sy + sh - 12, 0.7f, Theme.FAINT, false);
	}

	private void drawContent(DrawContext ctx, int mx, int my) {
		int cx = wx + SIDEBAR + 14;
		int cw = ww - SIDEBAR - 14 - 14;

		List<ClientModule> list = ModuleManager.in(selected);
		RenderUtil.text(ctx, selected.label, cx, wy + 14, 1.35f, Theme.TEXT, false);
		RenderUtil.text(ctx, list.size() + (list.size() == 1 ? " module" : " modules"), cx, wy + 30, 0.8f, Theme.MUTED, false);

		scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentHeight - (contentBottom - contentTop))));
		scroll += (scrollTarget - scroll) * Math.min(1f, dt * 16f);
		if (Math.abs(scrollTarget - scroll) < 0.1f) scroll = scrollTarget;

		ctx.enableScissor(cx - 2, contentTop, cx + cw + 2, contentBottom);

		float y = contentTop - scroll;
		for (ClientModule m : list) {
			float target = open.contains(m) ? 1f : 0f;
			float a = openAnim.getOrDefault(m, 0f);
			a += (target - a) * Math.min(1f, dt * 12f);
			if (Math.abs(target - a) < 0.005f) a = target;
			openAnim.put(m, a);

			float eased = a * a * (3f - 2f * a);
			int panelH = m.hasPanel() ? panelHeight(m) : 0;
			int extra = Math.round(panelH * eased);
			int cardH = CARD_H + extra;
			int iy = Math.round(y);

			if (iy + cardH >= contentTop && iy <= contentBottom) {
				drawCard(ctx, m, cx, iy, cw, cardH, extra, a, mx, my);
			}
			y += cardH + 6;
		}
		contentHeight = y + scroll - contentTop;
		ctx.disableScissor();

		float view = contentBottom - contentTop;
		if (contentHeight > view + 1) {
			float thumbH = Math.max(18, view * view / contentHeight);
			float travel = view - thumbH;
			float t = scrollTarget / Math.max(1f, contentHeight - view);
			RenderUtil.roundRect(ctx, wx + ww - 9, contentTop + travel * t, 3, thumbH, 1, RenderUtil.alpha(Theme.accent(), 0.5f));
		}
	}

	private void drawCard(DrawContext ctx, ClientModule m, int x, int y, int w, int h, int extra, float openAmount, int mx, int my) {
		boolean inView = my >= contentTop && my < contentBottom;
		boolean hoverHeader = inView && mx >= x && mx < x + w - 46 && my >= y && my < y + CARD_H;

		int bg = openAmount > 0.02f ? Theme.CARD_OPEN : hoverHeader ? Theme.CARD_HOVER : Theme.CARD;
		RenderUtil.roundRect(ctx, x, y, w, h, 9, bg);

		if (m.toggleAnim > 0.02f) {
			RenderUtil.roundRect(ctx, x, y + 7, 2, CARD_H - 14, 1, RenderUtil.alpha(Theme.accent(), m.toggleAnim));
		}

		boolean hasPanel = m.hasPanel();
		int textRight = x + w - (hasPanel ? 78 : 56);
		RenderUtil.text(ctx, m.name, x + 12, y + 7, 1f, Theme.TEXT, false);
		RenderUtil.text(ctx, RenderUtil.trim(m.description, textRight - x - 12), x + 12, y + 19, 0.8f, Theme.MUTED, false);

		if (hasPanel) {
			String chev = openAmount > 0.5f ? "^" : "v";
			RenderUtil.text(ctx, chev, x + w - 62, y + 13, 1f, hoverHeader ? Theme.TEXT : Theme.FAINT, false);
		}

		// toggle switch
		int tx = x + w - 40, ty = y + 10;
		float a = m.alwaysOn ? 1f : m.toggleAnim;
		int track = RenderUtil.lerpArgb(Theme.OFF_TRACK, Theme.accent(), a);
		if (m.alwaysOn) track = RenderUtil.alpha(track, 0.45f);
		RenderUtil.roundRect(ctx, tx, ty, 28, 14, 7, track);
		RenderUtil.roundRect(ctx, tx + 2 + Math.round(14 * a), ty + 2, 10, 10, 5, 0xFFFFFFFF);

		hits.add(new Hit(tx - 4, y, 48, CARD_H, true, (px, b) -> m.toggle(), null));
		if (hasPanel) {
			hits.add(new Hit(x, y, w - 46, CARD_H, true, (px, b) -> {
				if (open.contains(m)) open.remove(m); else open.add(m);
				editing = null;
			}, null));
		} else {
			hits.add(new Hit(x, y, w - 46, CARD_H, true, (px, b) -> { if (b == 1) m.toggle(); }, null));
		}

		if (extra > 2) {
			ctx.enableScissor(x, y + CARD_H, x + w, y + CARD_H + extra);
			drawPanel(ctx, m, x, y + CARD_H, w, mx, my, openAmount >= 0.95f);
			ctx.disableScissor();
		}
	}

	private int panelHeight(ClientModule m) {
		int h = 12;
		if (!m.status.isEmpty()) h += 16;
		int extra = m.panelExtraHeight();
		if (extra > 0) h += extra + 6;
		h += m.actionRows.size() * 24;
		h += m.settings.size() * 22;
		return h;
	}

	private void drawPanel(DrawContext ctx, ClientModule m, int x, int y, int w, int mx, int my, boolean interactive) {
		int px = x + 10, pw = w - 20;
		int cy = y + 6;

		if (!m.status.isEmpty()) {
			RenderUtil.text(ctx, RenderUtil.trim(m.status, pw), px, cy + 2, 0.85f, Theme.FAINT, false);
			cy += 16;
		}
		int extra = m.panelExtraHeight();
		if (extra > 0) {
			m.renderPanelExtra(ctx, px, cy, pw);
			cy += extra + 6;
		}

		for (List<Action> row : m.actionRows) {
			int n = row.size();
			int gap = 6;
			int bw = (pw - gap * (n - 1)) / n;
			for (int i = 0; i < n; i++) {
				Action action = row.get(i);
				int bx = px + i * (bw + gap);
				boolean hov = interactive && inContent(mx, my) && mx >= bx && mx < bx + bw && my >= cy && my < cy + 20;
				RenderUtil.roundRect(ctx, bx, cy, bw, 20, 6, hov ? Theme.accentDim() : Theme.CARD_HOVER);
				RenderUtil.centered(ctx, action.label().get(), bx + bw / 2f, cy + 6, 0.95f, hov ? Theme.TEXT : Theme.MUTED, false);
				if (interactive) {
					hits.add(new Hit(bx, cy, bw, 20, true, (p, b) -> {
						if (b == 0) action.run().run();
					}, null));
				}
			}
			cy += 24;
		}

		for (Setting s : m.settings) {
			drawSetting(ctx, s, px, cy, pw, mx, my, interactive);
			cy += 22;
		}
	}

	private void drawSetting(DrawContext ctx, Setting s, int px, int cy, int pw, int mx, int my, boolean interactive) {
		RenderUtil.text(ctx, s.name, px, cy + 7, 0.95f, Theme.MUTED, false);
		int right = px + pw;

		switch (s.type) {
			case BOOL -> {
				float target = s.getBool() ? 1f : 0f;
				float a = boolAnim.getOrDefault(s, target);
				a += (target - a) * Math.min(1f, dt * 16f);
				boolAnim.put(s, a);
				int bx = right - 26;
				RenderUtil.roundRect(ctx, bx, cy + 5, 26, 12, 6, RenderUtil.lerpArgb(Theme.OFF_TRACK, Theme.accent(), a));
				RenderUtil.roundRect(ctx, bx + 2 + Math.round(14 * a), cy + 7, 8, 8, 4, 0xFFFFFFFF);
				if (interactive) {
					hits.add(new Hit(bx - 4, cy, 34, 22, true, (p, b) -> { if (b == 0) s.setBool(!s.getBool()); }, null));
				}
			}
			case NUMBER -> {
				int sliderW = Math.min(110, pw / 2);
				int sx = right - 40 - sliderW;
				double t = (s.getNumber() - s.min) / Math.max(1e-9, s.max - s.min);
				RenderUtil.roundRect(ctx, sx, cy + 9, sliderW, 4, 2, Theme.OFF_TRACK);
				RenderUtil.roundRect(ctx, sx, cy + 9, Math.max(3, (int) Math.round(sliderW * t)), 4, 2, Theme.accent());
				RenderUtil.roundRect(ctx, sx + (int) Math.round(sliderW * t) - 4, cy + 7, 8, 8, 4, 0xFFFFFFFF);
				String v = s.display();
				RenderUtil.text(ctx, v, right - RenderUtil.width(v, 0.9f), cy + 7, 0.9f, Theme.TEXT, false);
				if (interactive) {
					Consumer<Double> set = px2 -> {
						double f = Math.max(0, Math.min(1, (px2 - sx) / sliderW));
						s.setNumber(s.min + f * (s.max - s.min));
					};
					hits.add(new Hit(sx - 4, cy, sliderW + 8, 22, true, (p, b) -> { if (b == 0) set.accept(p); }, set));
				}
			}
			case COLOR -> {
				int barW = Math.min(120, pw / 2);
				int bx = right - barW;
				for (int i = 0; i < barW; i++) {
					ctx.fill(bx + i, cy + 7, bx + i + 1, cy + 15, 0xFF000000 | ColorUtil.hsb(i / (float) barW, 0.55f, 1f));
				}
				int kx = bx + (int) Math.round(s.getHue() * (barW - 1));
				RenderUtil.roundRect(ctx, kx - 2, cy + 4, 4, 14, 2, 0xFFFFFFFF);
				if (interactive) {
					Consumer<Double> set = px2 -> s.setNumber(Math.max(0, Math.min(1, (px2 - bx) / (barW - 1.0))));
					hits.add(new Hit(bx - 3, cy, barW + 6, 22, true, (p, b) -> { if (b == 0) set.accept(p); }, set));
				}
			}
			case MODE -> {
				String label = s.getMode();
				int bw = Math.max(72, RenderUtil.width(label, 0.95f) + 22);
				int bx = right - bw;
				boolean hov = interactive && inContent(mx, my) && mx >= bx && mx < bx + bw && my >= cy + 2 && my < cy + 20;
				RenderUtil.roundRect(ctx, bx, cy + 2, bw, 18, 6, hov ? Theme.CARD_HOVER : Theme.CARD);
				RenderUtil.centered(ctx, label, bx + bw / 2f, cy + 7, 0.95f, Theme.accent(), false);
				RenderUtil.text(ctx, "<", bx + 5, cy + 7, 0.9f, Theme.FAINT, false);
				RenderUtil.text(ctx, ">", bx + bw - 5 - RenderUtil.width(">", 0.9f), cy + 7, 0.9f, Theme.FAINT, false);
				if (interactive) {
					hits.add(new Hit(bx, cy + 2, bw, 18, true, (p, b) -> {
						if (b == 0) s.cycle(1);
						else if (b == 1) s.cycle(-1);
					}, null));
				}
			}
			case TEXT -> {
				int fw = pw - 92;
				int fx = right - fw;
				boolean isEditing = editing == s;
				RenderUtil.roundRectBordered(ctx, fx, cy + 2, fw, 18, 6, Theme.CARD, isEditing ? Theme.accent() : Theme.BORDER);
				String text = s.getText();
				int maxW = fw - 12;
				if (text.isEmpty() && !isEditing) {
					RenderUtil.text(ctx, RenderUtil.trim(s.hint, maxW), fx + 6, cy + 7, 0.85f, Theme.FAINT, false);
				} else {
					String shown = text;
					boolean cut = false;
					while (RenderUtil.width(shown, 0.95f) > maxW - 4 && shown.length() > 1) {
						shown = shown.substring(1);
						cut = true;
					}
					if (cut) shown = "..." + shown.substring(Math.min(3, shown.length()));
					RenderUtil.text(ctx, shown, fx + 6, cy + 7, 0.95f, Theme.TEXT, false);
					if (isEditing && (System.currentTimeMillis() / 500) % 2 == 0) {
						int cxp = fx + 6 + RenderUtil.width(shown, 0.95f) + 1;
						ctx.fill(cxp, cy + 5, cxp + 1, cy + 17, Theme.accent());
					}
				}
				if (isEditing && text.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
					ctx.fill(fx + 6, cy + 5, fx + 7, cy + 17, Theme.accent());
				}
				if (interactive) {
					hits.add(new Hit(fx, cy + 2, fw, 18, true, (p, b) -> { if (b == 0) editing = s; }, null));
				}
			}
		}
	}

	private boolean inContent(double mx, double my) {
		return my >= contentTop && my < contentBottom;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		Setting before = editing;
		editing = null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (!h.contains(mx, my)) continue;
			if (h.content() && !inContent(mx, my)) continue;
			h.click().accept(mx, button);
			if (h.drag() != null && button == 0) activeDrag = h;
			return true;
		}
		if (before != null) Config.save();
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (activeDrag != null && button == 0) {
			activeDrag.drag().accept(mx);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (activeDrag != null) {
			activeDrag = null;
			Config.save();
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (inContent(mx, my) && mx > wx + SIDEBAR) {
			scrollTarget -= (float) (vertical * 26);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (editing != null) {
			switch (keyCode) {
				case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					editing = null;
					Config.save();
				}
				case GLFW.GLFW_KEY_BACKSPACE -> {
					String t = editing.getText();
					if (hasControlDown()) editing.setText("");
					else if (!t.isEmpty()) editing.setText(t.substring(0, t.length() - 1));
				}
				case GLFW.GLFW_KEY_V -> {
					if (hasControlDown()) {
						String clip = MinecraftClient.getInstance().keyboard.getClipboard();
						if (clip != null) {
							String cleaned = clip.replaceAll("[\\r\\n\\t]", "").trim();
							String merged = editing.getText() + cleaned;
							editing.setText(merged.substring(0, Math.min(256, merged.length())));
						}
					}
				}
				default -> { }
			}
			return true;
		}
		if (LumenClient.openGui != null && LumenClient.openGui.matchesKey(keyCode, scanCode)) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (editing != null) {
			if (chr >= 32 && chr != 127 && editing.getText().length() < 256) {
				editing.setText(editing.getText() + chr);
			}
			return true;
		}
		return super.charTyped(chr, modifiers);
	}
}
