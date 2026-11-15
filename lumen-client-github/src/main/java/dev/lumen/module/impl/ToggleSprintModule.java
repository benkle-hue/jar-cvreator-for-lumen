package dev.lumen.module.impl;

import dev.lumen.LumenClient;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import dev.lumen.ui.RenderUtil;
import dev.lumen.ui.Theme;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;

/** Keeps the sprint key "held" for you, exactly like vanilla's own sprint toggle accessibility option. */
public final class ToggleSprintModule extends ClientModule {
	private final Setting mode = add(Setting.mode("Mode", "Toggle Key", "Toggle Key", "Always Sprint"));
	private final Setting showStatus = add(Setting.bool("Show Status", true));

	private boolean toggled;
	private boolean forcing;

	public ToggleSprintModule() {
		super("Toggle Sprint", "Sprint without holding the key. Bind a toggle key in Controls.", Category.UTILITY);
		status = "Set the toggle key under Controls > Lumen Client.";
	}

	private boolean active() {
		return mode.getMode().equals("Always Sprint") || toggled;
	}

	@Override
	public void onTick(MinecraftClient mc) {
		while (LumenClient.sprint != null && LumenClient.sprint.wasPressed()) {
			toggled = !toggled;
		}
		if (mc.player == null) return;

		boolean wantForce = active() && mc.currentScreen == null
				&& mc.options.forwardKey.isPressed() && !mc.player.isSneaking();
		if (wantForce) {
			mc.options.sprintKey.setPressed(true);
			forcing = true;
		} else if (forcing) {
			release(mc);
		}
	}

	private void release(MinecraftClient mc) {
		forcing = false;
		int code = KeyBindingHelper.getBoundKeyOf(mc.options.sprintKey).getCode();
		boolean physical = code != -1 && InputUtil.isKeyPressed(mc.getWindow().getHandle(), code);
		mc.options.sprintKey.setPressed(physical);
	}

	@Override
	protected void onDisable() {
		toggled = false;
		if (forcing) release(MinecraftClient.getInstance());
	}

	@Override
	public void onHud(DrawContext ctx) {
		if (!showStatus.getBool() || !active()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.getDebugHud().shouldShowDebugHud()) return;

		String label = mode.getMode().equals("Always Sprint") ? "Sprint: Always" : "Sprint: Toggled";
		int w = RenderUtil.width(label, 1f) + 16;
		int x = 6;
		int y = ctx.getScaledWindowHeight() - 22;
		RenderUtil.roundRect(ctx, x, y, w, 16, 6, 0xCC0B0C11);
		RenderUtil.roundRect(ctx, x + 5, y + 6, 4, 4, 2, forcing ? Theme.accent() : Theme.FAINT);
		RenderUtil.text(ctx, label, x + 13, y + 4, 1f, Theme.TEXT, false);
	}
}
