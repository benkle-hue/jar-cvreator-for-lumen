package dev.lumen.module;

import dev.lumen.LumenClient;
import dev.lumen.module.impl.CloudsModule;
import dev.lumen.module.impl.CustomSkyModule;
import dev.lumen.module.impl.DiscordRpcModule;
import dev.lumen.module.impl.InterfaceModule;
import dev.lumen.module.impl.SpotifyModule;
import dev.lumen.module.impl.ToggleSprintModule;
import dev.lumen.module.impl.WeatherModule;
import dev.lumen.module.impl.ZoomModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public final class ModuleManager {
	private static final List<ClientModule> ALL = new ArrayList<>();

	private ModuleManager() {}

	public static void init() {
		ALL.add(new InterfaceModule());
		ALL.add(new ZoomModule());
		ALL.add(new ToggleSprintModule());
		ALL.add(new WeatherModule());
		ALL.add(new CloudsModule());
		ALL.add(new CustomSkyModule());
		ALL.add(new SpotifyModule());
		ALL.add(new DiscordRpcModule());
	}

	public static List<ClientModule> all() { return ALL; }

	public static List<ClientModule> in(ClientModule.Category category) {
		List<ClientModule> out = new ArrayList<>();
		for (ClientModule m : ALL) {
			if (m.category == category) out.add(m);
		}
		return out;
	}

	public static <T extends ClientModule> T get(Class<T> type) {
		for (ClientModule m : ALL) {
			if (type.isInstance(m)) return type.cast(m);
		}
		return null;
	}

	/** Applies the saved enabled states. Runs on the first tick, when the client is fully ready. */
	public static void start() {
		for (ClientModule m : ALL) {
			if (m.alwaysOn || m.pendingEnabled) safe(m, () -> m.setEnabled(true));
		}
	}

	public static void tick(MinecraftClient mc) {
		for (ClientModule m : ALL) {
			if (m.isEnabled()) safe(m, () -> m.onTick(mc));
		}
	}

	public static void renderHud(DrawContext ctx) {
		for (ClientModule m : ALL) {
			if (m.isEnabled()) safe(m, () -> m.onHud(ctx));
		}
	}

	public static void shutdown() {
		for (ClientModule m : ALL) safe(m, m::shutdown);
	}

	private static void safe(ClientModule m, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			LumenClient.LOG.error("Module {} failed", m.name, t);
		}
	}
}
