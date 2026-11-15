package dev.lumen;

import dev.lumen.config.Config;
import dev.lumen.integration.SpotifyService;
import dev.lumen.module.ModuleManager;
import dev.lumen.ui.ClickGuiScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumenClient implements ClientModInitializer {
	public static final String VERSION = "1.0.0";
	public static final Logger LOG = LoggerFactory.getLogger("Lumen");

	public static KeyBinding openGui;
	public static KeyBinding zoom;
	public static KeyBinding sprint;
	public static KeyBinding spotifyPlay;
	public static KeyBinding spotifyNext;
	public static KeyBinding spotifyPrev;

	private static boolean started;

	@Override
	public void onInitializeClient() {
		ModuleManager.init();
		Config.load();

		openGui = key("key.lumen.gui", GLFW.GLFW_KEY_RIGHT_SHIFT);
		zoom = key("key.lumen.zoom", GLFW.GLFW_KEY_C);
		sprint = key("key.lumen.sprint", GLFW.GLFW_KEY_UNKNOWN);
		spotifyPlay = key("key.lumen.spotify.play", GLFW.GLFW_KEY_UNKNOWN);
		spotifyNext = key("key.lumen.spotify.next", GLFW.GLFW_KEY_UNKNOWN);
		spotifyPrev = key("key.lumen.spotify.prev", GLFW.GLFW_KEY_UNKNOWN);

		ClientTickEvents.END_CLIENT_TICK.register(LumenClient::tick);

		HudRenderCallback.EVENT.register((ctx, counter) -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.options.hudHidden) return;
			ModuleManager.renderHud(ctx);
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Config.save();
			ModuleManager.shutdown();
		});

		LOG.info("Lumen Client {} loaded", VERSION);
	}

	private static KeyBinding key(String id, int code) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, code, "category.lumen.keys"));
	}

	private static void tick(MinecraftClient mc) {
		if (!started) {
			started = true;
			ModuleManager.start();
		}

		while (openGui.wasPressed()) {
			if (mc.currentScreen == null) mc.setScreen(new ClickGuiScreen());
		}
		while (spotifyPlay.wasPressed()) SpotifyService.playPause();
		while (spotifyNext.wasPressed()) SpotifyService.next();
		while (spotifyPrev.wasPressed()) SpotifyService.previous();

		ModuleManager.tick(mc);
	}
}
