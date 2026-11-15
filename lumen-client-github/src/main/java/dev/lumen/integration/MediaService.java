package dev.lumen.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumen.LumenClient;
import dev.lumen.config.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * Reads whatever is playing from the Windows media session (the same thing the media overlay and
 * keyboard media keys use): Firefox, Chrome, Edge, the Spotify app, VLC, ... No login or API key.
 * A small PowerShell helper polls Windows and prints one JSON line per update.
 */
public final class MediaService {
	private static final String SCRIPT = """
			$ErrorActionPreference = 'SilentlyContinue'
			[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
			$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
			$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]

			$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
			function Await($op, $type) {
			    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
			    if ($task.Wait(4000)) { return $task.Result }
			    return $null
			}

			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			if ($null -eq $mgr) { exit 2 }

			$cmdFile = Join-Path $PSScriptRoot 'media.cmd'
			$lastKey = ''

			function Pick {
			    foreach ($s in $mgr.GetSessions()) {
			        if ($s.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing') { return $s }
			    }
			    return $mgr.GetCurrentSession()
			}

			while ($true) {
			    try {
			        if (Test-Path -LiteralPath $cmdFile) {
			            $c = (Get-Content -LiteralPath $cmdFile -Raw).Trim()
			            Remove-Item -LiteralPath $cmdFile -Force
			            $t = Pick
			            if ($null -ne $t) {
			                if ($c -eq 'toggle') { $null = Await ($t.TryTogglePlayPauseAsync()) ([bool]) }
			                elseif ($c -eq 'next') { $null = Await ($t.TrySkipNextAsync()) ([bool]) }
			                elseif ($c -eq 'prev') { $null = Await ($t.TrySkipPreviousAsync()) ([bool]) }
			            }
			        }

			        $s = Pick
			        if ($null -eq $s) {
			            [Console]::Out.WriteLine('{"none":true}')
			        } else {
			            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			            $info = $s.GetPlaybackInfo()
			            $tl = $s.GetTimelineProperties()
			            $key = "$($s.SourceAppUserModelId)|$($props.Title)|$($props.Artist)"
			            $thumbOut = ''
			            if ($key -ne $lastKey) {
			                $lastKey = $key
			                if ($null -ne $props.Thumbnail) {
			                    $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
			                    if ($null -ne $stream) {
			                        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
			                        $ms = New-Object System.IO.MemoryStream
			                        $net.CopyTo($ms)
			                        $thumbOut = [Convert]::ToBase64String($ms.ToArray())
			                        $net.Dispose()
			                        $ms.Dispose()
			                    }
			                }
			            }
			            $obj = [ordered]@{
			                app = $s.SourceAppUserModelId
			                title = $props.Title
			                artist = $props.Artist
			                album = $props.AlbumTitle
			                status = $info.PlaybackStatus.ToString()
			                pos = [int64]$tl.Position.TotalMilliseconds
			                dur = [int64]($tl.EndTime - $tl.StartTime).TotalMilliseconds
			                thumb = $thumbOut
			            }
			            [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress))
			        }
			    } catch { }
			    Start-Sleep -Milliseconds 500
			}
			""";

	private static Process process;
	private static volatile boolean running;
	private static volatile String status = "";

	private static final CoverArt cover = new CoverArt("media");

	private static volatile boolean hasTrack;
	private static volatile boolean playing;
	private static volatile String title = "", artist = "", album = "", app = "";
	private static volatile long duration, baseProgress, baseTime, lastUpdate;
	private static volatile String trackKey = "";

	private MediaService() {}

	public static boolean isRunning() { return running; }

	public static String status() { return status; }

	public static synchronized void start() {
		if (running) return;
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			status = "Browser media needs Windows";
			return;
		}
		try {
			Path script = Config.dir().resolve("media.ps1");
			Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
			ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
					"-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString());
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process p = pb.start();
			process = p;
			running = true;
			status = "";
			Thread reader = new Thread(() -> read(p), "Lumen-Media");
			reader.setDaemon(true);
			reader.start();
		} catch (Exception e) {
			status = "Could not start media reader";
			LumenClient.LOG.warn("Media reader failed to start", e);
		}
	}

	public static synchronized void stop() {
		running = false;
		hasTrack = false;
		if (process != null) {
			process.destroyForcibly();
			process = null;
		}
		cover.clear();
		trackKey = "";
	}

	public static void command(String name) {
		if (!running) return;
		try {
			Files.writeString(Config.dir().resolve("media.cmd"), name);
		} catch (Exception ignored) {
			// next click will retry
		}
	}

	public static SpotifyService.Track current() {
		if (!running || !hasTrack || System.currentTimeMillis() - lastUpdate > 5000) return null;
		long progress = baseProgress;
		if (playing) progress += System.currentTimeMillis() - baseTime;
		if (duration > 0) progress = Math.min(progress, duration);
		return new SpotifyService.Track(title, artist, album, duration, Math.max(0, progress),
				playing, cover.id(), cover.tint(), label(app));
	}

	private static void read(Process p) {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = br.readLine()) != null) {
				handle(line.replace("﻿", "").trim());
			}
		} catch (Exception ignored) {
			// process was stopped
		}
		if (running) {
			running = false;
			hasTrack = false;
			status = "Media reader stopped (Windows only, needs PowerShell)";
		}
	}

	private static void handle(String line) {
		if (!line.startsWith("{")) return;
		try {
			JsonObject o = JsonParser.parseString(line).getAsJsonObject();
			if (o.has("none")) {
				hasTrack = false;
				return;
			}
			String newApp = str(o, "app");
			String newTitle = str(o, "title");
			String newArtist = str(o, "artist");
			String state = str(o, "status");

			String key = newApp + "|" + newTitle + "|" + newArtist;
			if (!key.equals(trackKey)) {
				trackKey = key;
				cover.clear();
			}
			String thumb = str(o, "thumb");
			if (!thumb.isEmpty()) {
				try {
					cover.set(Base64.getDecoder().decode(thumb));
				} catch (IllegalArgumentException ignored) {
					// bad thumbnail, keep going without cover
				}
			}

			app = newApp;
			title = newTitle;
			artist = newArtist;
			album = str(o, "album");
			playing = state.equals("Playing");
			duration = o.has("dur") && !o.get("dur").isJsonNull() ? Math.max(0, o.get("dur").getAsLong()) : 0;
			baseProgress = o.has("pos") && !o.get("pos").isJsonNull() ? Math.max(0, o.get("pos").getAsLong()) : 0;
			baseTime = System.currentTimeMillis();
			lastUpdate = baseTime;
			hasTrack = !newTitle.isEmpty() && !state.equals("Closed") && !state.equals("Stopped");
		} catch (Exception e) {
			LumenClient.LOG.debug("Bad media line: {}", e.toString());
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** Friendly name for the app that owns the media session. */
	static String label(String id) {
		String l = id.toLowerCase(Locale.ROOT);
		if (l.contains("firefox") || l.equals("308046b0af4a39cb")) return "Firefox";
		if (l.contains("chrome")) return "Chrome";
		if (l.contains("msedge") || l.contains("edge")) return "Edge";
		if (l.contains("brave")) return "Brave";
		if (l.contains("opera")) return "Opera";
		if (l.contains("spotify")) return "Spotify";
		if (l.contains("vlc")) return "VLC";
		if (l.contains("zunemusic") || l.contains("music.ui")) return "Media Player";
		if (l.endsWith(".exe")) return id.substring(0, id.length() - 4);
		return id.isEmpty() ? "Media" : id;
	}
}
