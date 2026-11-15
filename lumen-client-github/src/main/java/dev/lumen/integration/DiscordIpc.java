package dev.lumen.integration;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Minimal Discord Rich Presence client that talks to the local Discord app directly
 * (named pipe on Windows, unix socket elsewhere). No native libraries needed.
 */
public final class DiscordIpc implements Closeable {
	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;

	private Closeable connection;
	private InputStream in;
	private OutputStream out;

	public boolean isOpen() { return connection != null; }

	public void connect(String clientId) throws IOException {
		IOException last = null;
		for (int i = 0; i < 10 && connection == null; i++) {
			try {
				open(i);
			} catch (IOException e) {
				last = e;
			}
		}
		if (connection == null) throw last != null ? last : new IOException("Discord is not running");

		JsonObject hello = new JsonObject();
		hello.addProperty("v", 1);
		hello.addProperty("client_id", clientId);
		try {
			send(OP_HANDSHAKE, hello.toString());
			readFrame();
		} catch (IOException e) {
			close();
			throw e;
		}
	}

	public void setActivity(JsonObject activity) throws IOException {
		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		if (activity != null) args.add("activity", activity);

		JsonObject payload = new JsonObject();
		payload.addProperty("cmd", "SET_ACTIVITY");
		payload.add("args", args);
		payload.addProperty("nonce", UUID.randomUUID().toString());
		send(OP_FRAME, payload.toString());
		readFrame();
	}

	@Override
	public void close() {
		Closeable c = connection;
		connection = null;
		in = null;
		out = null;
		if (c != null) {
			try {
				c.close();
			} catch (IOException ignored) {
				// already gone
			}
		}
	}

	// ---------------------------------------------------------------- transport

	private void open(int index) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			RandomAccessFile pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw");
			connection = pipe;
			in = new InputStream() {
				@Override public int read() throws IOException { return pipe.read(); }
				@Override public int read(byte[] b, int off, int len) throws IOException { return pipe.read(b, off, len); }
			};
			out = new OutputStream() {
				@Override public void write(int b) throws IOException { pipe.write(b); }
				@Override public void write(byte[] b, int off, int len) throws IOException { pipe.write(b, off, len); }
			};
			return;
		}

		String base = firstNonEmpty(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"),
				System.getenv("TMP"), System.getenv("TEMP"), "/tmp");
		String[] candidates = {
				base + "/discord-ipc-" + index,
				base + "/app/com.discordapp.Discord/discord-ipc-" + index,
				base + "/snap.discord/discord-ipc-" + index
		};
		IOException last = null;
		for (String candidate : candidates) {
			Path path = Path.of(candidate);
			if (!Files.exists(path)) continue;
			SocketChannel channel = null;
			try {
				channel = SocketChannel.open(StandardProtocolFamily.UNIX);
				channel.connect(UnixDomainSocketAddress.of(path));
				connection = channel;
				in = Channels.newInputStream(channel);
				out = Channels.newOutputStream(channel);
				return;
			} catch (IOException e) {
				last = e;
				if (channel != null) channel.close();
			}
		}
		throw last != null ? last : new IOException("no Discord socket found");
	}

	private void send(int op, String json) throws IOException {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(op).putInt(payload.length).put(payload);
		out.write(buf.array());
		out.flush();
	}

	private String readFrame() throws IOException {
		byte[] header = in.readNBytes(8);
		if (header.length < 8) throw new IOException("Discord closed the connection");
		ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		h.getInt();
		int length = h.getInt();
		if (length < 0 || length > 1 << 20) throw new IOException("bad frame");
		byte[] body = in.readNBytes(length);
		return new String(body, StandardCharsets.UTF_8);
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (v != null && !v.isEmpty()) return v;
		}
		return "/tmp";
	}
}
