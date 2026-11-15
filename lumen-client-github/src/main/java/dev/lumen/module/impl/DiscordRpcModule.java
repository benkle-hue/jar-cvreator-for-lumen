package dev.lumen.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lumen.integration.DiscordIpc;
import dev.lumen.integration.NowPlaying;
import dev.lumen.integration.SpotifyService;
import dev.lumen.module.ClientModule;
import dev.lumen.module.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence, modelled on Vencord's CustomRPC plugin: you choose the activity type
 * (Playing / Listening / Watching / Competing), the name, two text lines with placeholders, images,
 * up to two buttons and the timer.
 *
 * Vencord injects the activity directly into Discord's client. We go through Discord's local IPC
 * instead, so we send the same fields and Discord decides what it displays. Application ID is still
 * required (free, https://discord.com/developers/applications).
 */
public final class DiscordRpcModule extends ClientModule {
	private static final long MIN_INTERVAL_MS = 15_000;

	private final Setting appId = add(Setting.text("Application ID", "", "Discord Developer Portal > Application ID"));
	private final Setting content = add(Setting.mode("Content", "Auto", "Auto", "Custom"));
	private final Setting type = add(Setting.mode("Activity Type", "Playing", "Playing", "Listening", "Watching", "Competing"));
	private final Setting memberList = add(Setting.mode("Member List Shows", "Name", "Name", "Details", "State"));
	private final Setting name = add(Setting.text("Name", "Minecraft", "Shown as: Playing <Name>"));
	private final Setting details = add(Setting.text("Details", "{status}", "Line 1. Try {status} {server} {music} {fps}"));
	private final Setting state = add(Setting.text("State", "{music}", "Line 2. Try {track} {artist} {source} {time}"));
	private final Setting largeImage = add(Setting.text("Large Image", "", "Asset key or https image URL"));
	private final Setting largeText = add(Setting.text("Large Image Text", "", "Tooltip on the large image"));
	private final Setting smallImage = add(Setting.text("Small Image", "", "Asset key or https image URL"));
	private final Setting smallText = add(Setting.text("Small Image Text", "", "Tooltip on the small image"));
	private final Setting timer = add(Setting.mode("Timer", "Elapsed", "None", "Elapsed", "Music Progress"));
	private final Setting button1Label = add(Setting.text("Button 1 Label", "", "Optional"));
	private final Setting button1Url = add(Setting.text("Button 1 URL", "", "https://..."));
	private final Setting button2Label = add(Setting.text("Button 2 Label", "", "Optional"));
	private final Setting button2Url = add(Setting.text("Button 2 URL", "", "https://..."));
	private final Setting showServer = add(Setting.bool("Show Server Address", false));

	private final DiscordIpc ipc = new DiscordIpc();
	private ExecutorService worker;

	private volatile boolean busy;
	private long startSeconds;
	private long lastAttempt;
	private volatile String lastKey = "";
	private int tickCounter;

	public DiscordRpcModule() {
		super("Discord RPC", "Custom Rich Presence like Vencord's CustomRPC: type, text, images, buttons.", Category.INTEGRATIONS);
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
		worker().execute(() -> {
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

		SpotifyService.Track track = NowPlaying.current();
		Map<String, String> vars = variables(mc, track);
		String[] keyOut = new String[1];
		JsonObject activity = buildActivity(vars, track, keyOut);

		long now = System.currentTimeMillis();
		boolean changed = !keyOut[0].equals(lastKey);
		boolean disconnected = !ipc.isOpen();
		if (!(changed || disconnected) || now - lastAttempt < MIN_INTERVAL_MS) return;

		lastAttempt = now;
		lastKey = keyOut[0];
		busy = true;
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

	// ---------------------------------------------------------------- content

	private Map<String, String> variables(MinecraftClient mc, SpotifyService.Track t) {
		String where;
		String serverText = "a server";
		if (mc.world == null) {
			where = "In the menus";
		} else if (mc.isInSingleplayer()) {
			where = "Playing singleplayer";
		} else {
			ServerInfo server = mc.getCurrentServerEntry();
			where = "Playing multiplayer";
			if (showServer.getBool() && server != null) serverText = server.address;
		}

		boolean listening = t != null && t.playing();
		Map<String, String> v = new LinkedHashMap<>();
		v.put("status", where);
		v.put("server", serverText);
		v.put("track", listening ? t.title() : "");
		v.put("artist", listening ? t.artist() : "");
		v.put("source", listening ? t.source() : "");
		v.put("music", !listening ? "" : t.artist().isEmpty() ? t.title() : t.title() + " - " + t.artist());
		v.put("fps", mc.getCurrentFps() + " FPS");
		LocalTime now = LocalTime.now();
		v.put("time", String.format("%02d:%02d", now.getHour(), now.getMinute()));
		return v;
	}

	private static String fill(String template, Map<String, String> vars) {
		String s = template;
		for (Map.Entry<String, String> e : vars.entrySet()) {
			s = s.replace("{" + e.getKey() + "}", e.getValue());
		}
		return s.replaceAll("\\s+", " ").trim();
	}

	/** Discord needs 2 to 128 characters per line. */
	private static String line(String s) {
		if (s.length() > 128) s = s.substring(0, 128);
		if (s.length() == 1) s = s + "​";
		return s;
	}

	private JsonObject buildActivity(Map<String, String> vars, SpotifyService.Track t, String[] keyOut) {
		boolean custom = content.getMode().equals("Custom");
		JsonObject a = new JsonObject();

		String line1;
		String line2;
		if (custom) {
			a.addProperty("type", switch (type.getMode()) {
				case "Listening" -> 2;
				case "Watching" -> 3;
				case "Competing" -> 5;
				default -> 0;
			});
			String n = name.getText().trim();
			if (!n.isEmpty()) a.addProperty("name", line(n));
			a.addProperty("status_display_type", switch (memberList.getMode()) {
				case "Details" -> 2;
				case "State" -> 1;
				default -> 0;
			});
			line1 = fill(details.getText(), vars);
			line2 = fill(state.getText(), vars);
		} else {
			a.addProperty("type", 0);
			line1 = vars.get("status");
			line2 = vars.get("music").isEmpty() ? "Lumen Client" : "Listening to " + vars.get("music");
		}
		if (!line1.isEmpty()) a.addProperty("details", line(line1));
		if (!line2.isEmpty()) a.addProperty("state", line(line2));

		JsonObject assets = new JsonObject();
		String big = largeImage.getText().trim();
		if (!big.isEmpty()) {
			assets.addProperty("large_image", big);
			String text = fill(largeText.getText(), vars);
			if (!text.isEmpty()) assets.addProperty("large_text", line(text));
		}
		String small = smallImage.getText().trim();
		if (!small.isEmpty()) {
			assets.addProperty("small_image", small);
			String text = fill(smallText.getText(), vars);
			if (!text.isEmpty()) assets.addProperty("small_text", line(text));
		}
		if (assets.size() > 0) a.add("assets", assets);

		JsonArray buttons = new JsonArray();
		addButton(buttons, button1Label.getText(), button1Url.getText());
		addButton(buttons, button2Label.getText(), button2Url.getText());
		if (buttons.size() > 0) a.add("buttons", buttons);

		// Everything above decides when we need to push an update; timestamps are added after so a
		// moving music position does not cause an update every second.
		keyOut[0] = a + "|" + (t == null ? "" : t.title() + t.playing()) + "|" + timer.getMode();

		JsonObject ts = new JsonObject();
		switch (timer.getMode()) {
			case "Elapsed" -> ts.addProperty("start", startSeconds);
			case "Music Progress" -> {
				if (t != null && t.playing()) {
					long start = (System.currentTimeMillis() - t.progressMs()) / 1000L;
					ts.addProperty("start", start);
					if (t.durationMs() > 0) ts.addProperty("end", start + t.durationMs() / 1000L);
				}
			}
			default -> { }
		}
		if (ts.size() > 0) a.add("timestamps", ts);
		return a;
	}

	private static void addButton(JsonArray buttons, String label, String url) {
		label = label.trim();
		url = url.trim();
		if (label.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) return;
		JsonObject b = new JsonObject();
		b.addProperty("label", label.length() > 32 ? label.substring(0, 32) : label);
		b.addProperty("url", url);
		buttons.add(b);
	}
}
