package dev.lumen.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Base class of every Lumen feature. (Named ClientModule to avoid clashing with java.lang.Module.) */
public abstract class ClientModule {
	public enum Category {
		VISUALS("Visuals"),
		UTILITY("Utility"),
		INTEGRATIONS("Integrations"),
		CLIENT("Client");

		public final String label;

		Category(String label) { this.label = label; }
	}

	public record Action(Supplier<String> label, Runnable run) {
		public static Action of(String label, Runnable run) { return new Action(() -> label, run); }
	}

	public final String name;
	public final String description;
	public final Category category;

	public final List<Setting> settings = new ArrayList<>();
	public final List<List<Action>> actionRows = new ArrayList<>();

	/** Free text shown at the top of the module's settings panel. */
	public String status = "";
	/** Modules that cannot be switched off (e.g. the interface theme). */
	public boolean alwaysOn;
	/** Set by the config loader; applied on the first client tick. */
	public boolean pendingEnabled;
	/** GUI animation state (0..1). */
	public float toggleAnim;

	private boolean enabled;

	protected ClientModule(String name, String description, Category category) {
		this.name = name;
		this.description = description;
		this.category = category;
	}

	protected <T extends Setting> T add(T setting) {
		settings.add(setting);
		return setting;
	}

	protected void addActionRow(Action... actions) {
		actionRows.add(List.of(actions));
	}

	public boolean isEnabled() { return enabled; }

	public final void setEnabled(boolean value) {
		if (value == enabled) return;
		enabled = value;
		if (value) onEnable(); else onDisable();
	}

	public final void toggle() { if (!alwaysOn) setEnabled(!enabled); }

	/** Called when the game closes. Does not change the saved enabled state. */
	public final void shutdown() { if (enabled) onDisable(); }

	public boolean hasPanel() {
		return !settings.isEmpty() || !actionRows.isEmpty() || !status.isEmpty();
	}

	protected void onEnable() {}
	protected void onDisable() {}
	public void onTick(MinecraftClient mc) {}
	public void onHud(DrawContext ctx) {}
	public void onGuiClose() {}

	/** Extra pixels of custom content (e.g. a now-playing card) at the top of the settings panel. */
	public int panelExtraHeight() { return 0; }
	public void renderPanelExtra(DrawContext ctx, int x, int y, int w) {}
}
