package dev.lumen.module.impl;

import com.google.gson.JsonObject;
import dev.lumen.integration.DiscordIpc;
import dev.lumen.integration.SpotifyService;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence. Discord requires an Application ID, which only you can create
 * (free, 1 minute, https://discord.com/developers/applications). The ID is public, not a secret.
 */
public final class DiscordRpcModule extends ClientModule {
	private static final long MIN_INTERVAL_MS = 15_000;

	private final Setting appId = add(Setting.text("Application ID", "", "Discord Developer Portal > Application ID"));
	private final Setting showServer = add(Setting.bool("Show Server Address", false));
	private final Setting showSpotify = add(Setting.bool("Show Spotify Track", false));
	private final Setting elapsed = add(Setting.bool("Show Elapsed Time", true));
	private final Setting imageKey = add(Setting.text("Large Image Key", "", "Asset name from Rich Presence > Art Assets"));

	private final DiscordIpc ipc = new DiscordIpc();
	private ExecutorService worker;

	private volatile boolean busy;
	private long startSeconds;
	private long lastAttempt;
	private String lastKey = "";
	private int tickCounter;

	public DiscordRpcModule() {
		super("Discord RPC", "Shows what you are playing in your Discord profile.", Category.INTEGRATIONS);
		status = "Paste your Discord Application ID to start.";
	}

	private synchronized ExecutorService worker() {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "Lumen-Discord");
				t.setDaemon(true);
				return t;
			});
		}
		return worker;
	}

	@Override
	protected void onEnable() {
		startSeconds = System.currentTimeMillis() / 1000L;
		lastAttempt = 0;
		lastKey = "";
	}

	@Override
	protected void onDisable() {
		ExecutorService w = worker();
		w.execute(() -> {
			try {
				if (ipc.isOpen()) ipc.setActivity(null);
			} catch (Exception ignored) {
				// Discord already closed
			}
			ipc.close();
		});
		status = "Disabled";
	}

	@Override
	public void onTick(MinecraftClient mc) {
		if ((tickCounter++ % 20) != 0) return;

		String id = appId.getText().trim();
		if (!id.matches("\\d{15,25}")) {
			status = id.isEmpty() ? "Paste your Discord Application ID to start." : "That does not look like an Application ID (digits only).";
			return;
		}
		if (busy) return;

		String[] lines = describe(mc);
		String key = lines[0] + "|" + lines[1] + "|" + imageKey.getText();
		long now = System.currentTimeMillis();
		boolean changed = !key.equals(lastKey);
		boolean disconnected = !ipc.isOpen();
		if (!(changed || disconnected) || now - lastAttempt < MIN_INTERVAL_MS) return;

		lastAttempt = now;
		lastKey = key;
		busy = true;
		JsonObject activity = buildActivity(lines);
		worker().execute(() -> {
			try {
				if (!ipc.isOpen()) ipc.connect(id);
				ipc.setActivity(activity);
				status = "Connected to Discord";
			} catch (Exception e) {
				ipc.close();
				lastKey = "";
				status = "Discord not found - is the desktop app running? Retrying...";
			} finally {
				busy = false;
			}
		});
	}

	private String[] describe(MinecraftClient mc) {
		String details;
		String state = "Lumen Client";
		if (mc.world == null) {
			details = "In the menus";
		} else if (mc.isInSingleplayer()) {
			details = "Playing singleplayer";
		} else {
			ServerInfo server = mc.getCurrentServerEntry();
			details = "Playing multiplayer";
			state = showServer.getBool() && server != null ? server.address : "On a server";
		}
		if (showSpotify.getBool()) {
			SpotifyService.Track t = SpotifyService.current();
			if (t != null && t.playing()) state = "Listening to " + t.title();
		}
		return new String[] { details, state };
	}

	private JsonObject buildActivity(String[] lines) {
		JsonObject a = new JsonObject();
		a.addProperty("details", lines[0]);
		a.addProperty("state", lines[1]);
		if (elapsed.getBool()) {
			JsonObject ts = new JsonObject();
			ts.addProperty("start", startSeconds);
			a.add("timestamps", ts);
		}
		String key = imageKey.getText().trim();
		if (!key.isEmpty()) {
			JsonObject assets = new JsonObject();
			assets.addProperty("large_image", key);
			assets.addProperty("large_text", "Lumen Client");
			a.add("assets", assets);
		}
		return a;
	}
}
