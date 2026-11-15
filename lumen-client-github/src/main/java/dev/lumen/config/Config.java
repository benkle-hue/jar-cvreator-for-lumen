package dev.lumen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumen.LumenClient;
import dev.lumen.module.ClientModule;
import dev.lumen.module.ModuleManager;
import dev.lumen.module.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves module states and settings to .minecraft/config/lumen/config.json. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Config() {}

	public static Path dir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("lumen");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			LumenClient.LOG.warn("Could not create config dir", e);
		}
		return dir;
	}

	public static void load() {
		Path file = dir().resolve("config.json");
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (ClientModule m : ModuleManager.all()) {
				if (!root.has(m.name) || !root.get(m.name).isJsonObject()) continue;
				JsonObject o = root.getAsJsonObject(m.name);
				if (o.has("enabled")) m.pendingEnabled = o.get("enabled").getAsBoolean();
				for (Setting s : m.settings) s.fromJson(o.get(s.name));
			}
		} catch (Exception e) {
			LumenClient.LOG.warn("Could not read config, using defaults", e);
		}
	}

	public static void save() {
		JsonObject root = new JsonObject();
		for (ClientModule m : ModuleManager.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.isEnabled());
			for (Setting s : m.settings) o.add(s.name, s.toJson());
			root.add(m.name, o);
		}
		try (Writer writer = Files.newBufferedWriter(dir().resolve("config.json"))) {
			GSON.toJson(root, writer);
		} catch (IOException e) {
			LumenClient.LOG.warn("Could not save config", e);
		}
	}
}
