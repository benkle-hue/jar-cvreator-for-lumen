package dev.lumen.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumen.LumenClient;
import dev.lumen.config.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spotify Web API client (Authorization Code + PKCE, so no client secret is ever needed).
 * All network work runs on a background thread; the render thread only reads volatile fields.
 */
public final class SpotifyService {
	public enum State { DISCONNECTED, CONNECTING, CONNECTED }

	public record Track(String title, String artist, String album, long durationMs, long progressMs,
						boolean playing, Identifier cover, int tint) {}

	private static final int PORT = 8898;
	private static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
	private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";
	private static final int DEFAULT_TINT = 0x1DB954;

	private static volatile State state = State.DISCONNECTED;
	private static volatile String message = "";
	private static volatile String clientId = "";

	private static volatile String accessToken = "";
	private static volatile String refreshToken = "";
	private static volatile long expiresAt;

	private static volatile boolean hasTrack;
	private static volatile boolean playing;
	private static volatile String title = "", artist = "", album = "", coverUrl = "", deviceName = "";
	private static volatile long duration, baseProgress, baseTime;
	private static volatile Identifier coverId;
	private static volatile int tint = DEFAULT_TINT;

	private static String loadedCoverUrl = "";
	private static NativeImageBackedTexture coverTexture;
	private static int coverCounter;

	private static ScheduledExecutorService pool;
	private static ScheduledFuture<?> pollTask;
	private static volatile boolean running;
	private static int pollCounter;

	private SpotifyService() {}

	// ---------------------------------------------------------------- public API

	public static State state() { return state; }

	public static boolean isConnected() { return state == State.CONNECTED; }

	public static String message() { return message; }

	public static String deviceName() { return deviceName; }

	public static synchronized void start(String id) {
		clientId = id == null ? "" : id.trim();
		running = true;
		if (pollTask == null || pollTask.isCancelled()) {
			pollTask = pool().scheduleWithFixedDelay(SpotifyService::pollSafe, 500, 1000, TimeUnit.MILLISECONDS);
		}
		if (state == State.DISCONNECTED && !clientId.isEmpty()) {
			String saved = loadSavedRefreshToken(clientId);
			if (!saved.isEmpty()) {
				refreshToken = saved;
				state = State.CONNECTING;
				message = "Reconnecting...";
				pool().execute(() -> {
					try {
						refresh();
						state = State.CONNECTED;
						message = "";
					} catch (Exception e) {
						state = State.DISCONNECTED;
						message = "Session expired - press Connect";
					}
				});
			}
		}
	}

	public static synchronized void stop() {
		running = false;
		if (pollTask != null) {
			pollTask.cancel(false);
			pollTask = null;
		}
	}

	public static void connect(String id) {
		String trimmed = id == null ? "" : id.trim();
		if (trimmed.isEmpty()) {
			message = "Paste your Spotify Client ID first";
			return;
		}
		if (state == State.CONNECTING) return;
		clientId = trimmed;
		state = State.CONNECTING;
		message = "Finish the login in your browser...";
		Thread t = new Thread(() -> {
			try {
				authFlow();
				state = State.CONNECTED;
				message = "";
			} catch (Exception e) {
				state = State.DISCONNECTED;
				message = "Login failed: " + e.getMessage();
				LumenClient.LOG.warn("Spotify login failed", e);
			}
		}, "Lumen-Spotify-Auth");
		t.setDaemon(true);
		t.start();
	}

	public static void disconnect() {
		state = State.DISCONNECTED;
		accessToken = "";
		refreshToken = "";
		hasTrack = false;
		playing = false;
		message = "";
		try {
			Files.deleteIfExists(tokenFile());
		} catch (IOException ignored) {
			// nothing to clean up
		}
	}

	/** Snapshot for rendering, or null if nothing is playing. Progress is interpolated locally. */
	public static Track current() {
		if (state != State.CONNECTED || !hasTrack) return null;
		long progress = baseProgress;
		if (playing) progress += System.currentTimeMillis() - baseTime;
		if (duration > 0) progress = Math.min(progress, duration);
		return new Track(title, artist, album, duration, Math.max(0, progress), playing, coverId, tint);
	}

	public static void playPause() {
		if (state != State.CONNECTED) return;
		boolean wasPlaying = playing;
		long now = System.currentTimeMillis();
		baseProgress = baseProgress + (wasPlaying ? now - baseTime : 0);
		baseTime = now;
		playing = !wasPlaying;
		command("PUT", wasPlaying ? "pause" : "play");
	}

	public static void next() {
		if (state == State.CONNECTED) command("POST", "next");
	}

	public static void previous() {
		if (state == State.CONNECTED) command("POST", "previous");
	}

	// ---------------------------------------------------------------- polling

	private static synchronized ScheduledExecutorService pool() {
		if (pool == null) {
			pool = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "Lumen-Spotify");
				t.setDaemon(true);
				return t;
			});
		}
		return pool;
	}

	private static volatile long holdMessageUntil;

	/** Command errors stay visible for a few seconds instead of being overwritten by the next poll. */
	private static void holdMessage(String text) {
		message = text;
		holdMessageUntil = System.currentTimeMillis() + 6000;
	}

	private static void pollMessage(String text) {
		if (System.currentTimeMillis() > holdMessageUntil) message = text;
	}

	private static void pollSafe() {
		try {
			if (!running || state != State.CONNECTED) return;
			// poll every second while playing, every third second while paused
			if (!playing && (pollCounter++ % 3) != 0) return;
			poll();
		} catch (Exception e) {
			LumenClient.LOG.debug("Spotify poll failed: {}", e.toString());
		}
	}

	private static void poll() throws IOException {
		ensureToken();
		Resp r = http("GET", "https://api.spotify.com/v1/me/player?additional_types=episode", accessToken, null, null);
		if (r.code == 204) {
			hasTrack = false;
			pollMessage("Nothing playing - start Spotify on any device");
			return;
		}
		if (r.code == 401) {
			refresh();
			return;
		}
		if (r.code == 429) return;
		if (r.code != 200) {
			pollMessage("Spotify error " + r.code);
			return;
		}
		JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
		JsonElement item = o.get("item");
		if (item == null || item.isJsonNull()) {
			hasTrack = false;
			return;
		}
		JsonObject track = item.getAsJsonObject();
		pollMessage("");
		title = str(track, "name");
		duration = track.has("duration_ms") && !track.get("duration_ms").isJsonNull() ? track.get("duration_ms").getAsLong() : 0;

		StringBuilder artists = new StringBuilder();
		if (track.has("artists") && track.get("artists").isJsonArray()) {
			for (JsonElement a : track.getAsJsonArray("artists")) {
				if (artists.length() > 0) artists.append(", ");
				artists.append(str(a.getAsJsonObject(), "name"));
			}
		} else if (track.has("show") && track.get("show").isJsonObject()) {
			artists.append(str(track.getAsJsonObject("show"), "name"));
		}
		artist = artists.toString();

		JsonObject albumObj = track.has("album") && track.get("album").isJsonObject() ? track.getAsJsonObject("album")
				: track.has("show") && track.get("show").isJsonObject() ? track.getAsJsonObject("show") : null;
		String url = "";
		if (albumObj != null) {
			album = str(albumObj, "name");
			url = pickImage(albumObj.get("images"));
		}
		if (o.has("device") && o.get("device").isJsonObject()) deviceName = str(o.getAsJsonObject("device"), "name");

		baseProgress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
		baseTime = System.currentTimeMillis();
		playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
		hasTrack = true;

		if (!url.isEmpty() && !url.equals(loadedCoverUrl)) {
			loadedCoverUrl = url;
			loadCover(url);
		}
		coverUrl = url;
	}

	private static String pickImage(JsonElement images) {
		if (images == null || !images.isJsonArray()) return "";
		JsonArray arr = images.getAsJsonArray();
		String best = "";
		int bestDiff = Integer.MAX_VALUE;
		for (JsonElement e : arr) {
			JsonObject img = e.getAsJsonObject();
			int w = img.has("width") && !img.get("width").isJsonNull() ? img.get("width").getAsInt() : 300;
			int diff = Math.abs(w - 300);
			if (diff < bestDiff) {
				bestDiff = diff;
				best = str(img, "url");
			}
		}
		return best;
	}

	private static void command(String method, String path) {
		pool().execute(() -> {
			try {
				ensureToken();
				Resp r = http(method, "https://api.spotify.com/v1/me/player/" + path, accessToken, null, null);
				if (r.code == 403) holdMessage("Playback control needs Spotify Premium");
				else if (r.code == 404) holdMessage("No active device - open Spotify and play something");
				else if (r.code >= 400) holdMessage("Spotify error " + r.code);
				else message = "";
				pool().schedule(() -> {
					try {
						poll();
					} catch (IOException ignored) {
						// next regular poll will retry
					}
				}, 350, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				message = "Command failed: " + e.getMessage();
			}
		});
	}

	// ---------------------------------------------------------------- auth

	private static void authFlow() throws Exception {
		SecureRandom rnd = new SecureRandom();
		byte[] raw = new byte[64];
		rnd.nextBytes(raw);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		String challenge = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		byte[] st = new byte[16];
		rnd.nextBytes(st);
		String stateParam = Base64.getUrlEncoder().withoutPadding().encodeToString(st);

		String authUrl = "https://accounts.spotify.com/authorize"
				+ "?response_type=code"
				+ "&client_id=" + enc(clientId)
				+ "&scope=" + enc(SCOPES)
				+ "&redirect_uri=" + enc(REDIRECT)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challenge
				+ "&state=" + enc(stateParam);

		String code = null;
		try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
			server.setSoTimeout(240_000);
			try {
				Util.getOperatingSystem().open(URI.create(authUrl));
			} catch (Exception e) {
				message = "Could not open the browser";
				LumenClient.LOG.warn("Could not open browser for Spotify login", e);
			}
			while (code == null) {
				try (Socket s = server.accept()) {
					s.setSoTimeout(5000);
					BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					String line = br.readLine();
					if (line == null || !line.startsWith("GET /callback")) {
						reply(s, 404, "Not found");
						continue;
					}
					String target = line.split(" ")[1];
					Map<String, String> q = query(target);
					if (q.containsKey("error")) {
						reply(s, 200, page("Login cancelled", "Spotify said: " + q.get("error") + ". You can close this tab."));
						throw new IOException(q.get("error"));
					}
					if (!stateParam.equals(q.get("state")) || q.get("code") == null) {
						reply(s, 400, page("Invalid request", "Please try connecting again from Minecraft."));
						throw new IOException("state mismatch");
					}
					code = q.get("code");
					reply(s, 200, page("Connected", "Lumen Client is now linked to Spotify. You can close this tab and go back to Minecraft."));
				}
			}
		}

		String body = "grant_type=authorization_code"
				+ "&code=" + enc(code)
				+ "&redirect_uri=" + enc(REDIRECT)
				+ "&client_id=" + enc(clientId)
				+ "&code_verifier=" + enc(verifier);
		Resp r = http("POST", "https://accounts.spotify.com/api/token", null,
				"application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
		if (r.code != 200) throw new IOException("token exchange failed (" + r.code + ")");
		applyTokens(r.text());
	}

	private static void refresh() throws IOException {
		if (refreshToken.isEmpty()) throw new IOException("no refresh token");
		String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId);
		Resp r = http("POST", "https://accounts.spotify.com/api/token", null,
				"application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
		if (r.code != 200) throw new IOException("refresh failed (" + r.code + ")");
		applyTokens(r.text());
	}

	private static void applyTokens(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		accessToken = str(o, "access_token");
		if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) refreshToken = o.get("refresh_token").getAsString();
		long seconds = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
		expiresAt = System.currentTimeMillis() + seconds * 1000L;
		saveRefreshToken();
	}

	private static void ensureToken() throws IOException {
		if (System.currentTimeMillis() > expiresAt - 30_000) refresh();
	}

	private static Path tokenFile() { return Config.dir().resolve("spotify.json"); }

	private static void saveRefreshToken() {
		JsonObject o = new JsonObject();
		o.addProperty("client_id", clientId);
		o.addProperty("refresh_token", refreshToken);
		try {
			Files.writeString(tokenFile(), o.toString());
		} catch (IOException e) {
			LumenClient.LOG.warn("Could not save Spotify session", e);
		}
	}

	private static String loadSavedRefreshToken(String id) {
		try {
			Path f = tokenFile();
			if (!Files.exists(f)) return "";
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			if (id.equals(str(o, "client_id"))) return str(o, "refresh_token");
		} catch (Exception ignored) {
			// treat as not saved
		}
		return "";
	}

	// ---------------------------------------------------------------- cover art

	private static void loadCover(String url) {
		pool().execute(() -> {
			try {
				Resp r = http("GET", url, null, null, null);
				if (r.code != 200) return;
				BufferedImage src = ImageIO.read(new ByteArrayInputStream(r.body));
				if (src == null) return;

				int newTint = dominantColor(src);

				BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(src, 0, 0, 128, 128, null);
				g.dispose();

				ByteArrayOutputStream png = new ByteArrayOutputStream();
				ImageIO.write(out, "png", png);
				NativeImage image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));

				MinecraftClient.getInstance().execute(() -> installCover(image, newTint));
			} catch (Exception e) {
				LumenClient.LOG.debug("Cover load failed: {}", e.toString());
			}
		});
	}

	private static void installCover(NativeImage image, int newTint) {
		MinecraftClient mc = MinecraftClient.getInstance();
		Identifier old = coverId;
		Identifier id = Identifier.of("lumen", "spotify/cover/" + (++coverCounter));
		NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
		mc.getTextureManager().registerTexture(id, texture);
		coverTexture = texture;
		coverId = id;
		tint = newTint;
		if (old != null) mc.getTextureManager().destroyTexture(old);
	}

	/** Saturation-weighted average colour, nudged so it always works as a bright accent on a dark card. */
	private static int dominantColor(BufferedImage src) {
		BufferedImage small = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = small.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, 24, 24, null);
		g.dispose();
		double r = 0, gr = 0, b = 0, total = 0;
		float[] hsb = new float[3];
		for (int y = 0; y < 24; y++) {
			for (int x = 0; x < 24; x++) {
				int rgb = small.getRGB(x, y);
				int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
				Color.RGBtoHSB(cr, cg, cb, hsb);
				double w = hsb[1] * hsb[2] + 0.05;
				r += cr * w;
				gr += cg * w;
				b += cb * w;
				total += w;
			}
		}
		if (total <= 0) return DEFAULT_TINT;
		Color.RGBtoHSB((int) (r / total), (int) (gr / total), (int) (b / total), hsb);
		float s = Math.max(0.45f, hsb[1]);
		float v = Math.max(0.75f, hsb[2]);
		return Color.HSBtoRGB(hsb[0], s, v) & 0xFFFFFF;
	}

	// ---------------------------------------------------------------- helpers

	private record Resp(int code, byte[] body) {
		String text() { return new String(body, StandardCharsets.UTF_8); }
	}

	private static Resp http(String method, String url, String bearer, String contentType, byte[] body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
		try {
			c.setRequestMethod(method);
			c.setConnectTimeout(6000);
			c.setReadTimeout(9000);
			c.setRequestProperty("User-Agent", "LumenClient/" + LumenClient.VERSION);
			if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
			if (body != null || method.equals("PUT") || method.equals("POST")) {
				byte[] data = body == null ? new byte[0] : body;
				c.setDoOutput(true);
				if (contentType != null) c.setRequestProperty("Content-Type", contentType);
				c.setFixedLengthStreamingMode(data.length);
				try (OutputStream out = c.getOutputStream()) {
					out.write(data);
				}
			}
			int code = c.getResponseCode();
			InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
			byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
			return new Resp(code, bytes);
		} finally {
			c.disconnect();
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

	private static Map<String, String> query(String target) {
		Map<String, String> map = new HashMap<>();
		int i = target.indexOf('?');
		if (i < 0) return map;
		for (String pair : target.substring(i + 1).split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) continue;
			map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return map;
	}

	private static void reply(Socket s, int code, String html) throws IOException {
		byte[] body = html.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
				+ "Content-Type: text/html; charset=utf-8\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Connection: close\r\n\r\n";
		OutputStream out = s.getOutputStream();
		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	private static String page(String heading, String text) {
		return "<!doctype html><meta charset=utf-8><title>Lumen</title>"
				+ "<body style=\"margin:0;height:100vh;display:grid;place-items:center;background:#0c0d12;color:#e8eaf2;font-family:system-ui,sans-serif\">"
				+ "<div style=\"text-align:center;padding:40px 48px;border-radius:18px;background:#14161d;border:1px solid #262a38\">"
				+ "<div style=\"font-size:13px;letter-spacing:.3em;color:#8b7bff\">LUMEN</div>"
				+ "<h1 style=\"margin:10px 0 8px;font-size:26px\">" + heading + "</h1>"
				+ "<p style=\"margin:0;color:#8b90a3\">" + text + "</p></div></body>";
	}
}
