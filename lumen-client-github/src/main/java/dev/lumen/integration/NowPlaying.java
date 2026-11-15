package dev.lumen.integration;

/**
 * Picks what to show: the Spotify API (if you connected it and it is playing), otherwise whatever
 * Windows reports as playing (Firefox, Chrome, Edge, Spotify app, ...).
 */
public final class NowPlaying {
	private record Choice(SpotifyService.Track track, boolean api) {}

	private NowPlaying() {}

	private static Choice choose() {
		SpotifyService.Track sp = SpotifyService.isConnected() ? SpotifyService.current() : null;
		SpotifyService.Track media = MediaService.current();
		if (sp != null && (sp.playing() || media == null || !media.playing())) return new Choice(sp, true);
		if (media != null) return new Choice(media, false);
		return new Choice(null, false);
	}

	public static SpotifyService.Track current() { return choose().track(); }

	public static void playPause() {
		if (choose().api()) SpotifyService.playPause(); else MediaService.command("toggle");
	}

	public static void next() {
		if (choose().api()) SpotifyService.next(); else MediaService.command("next");
	}

	public static void previous() {
		if (choose().api()) SpotifyService.previous(); else MediaService.command("prev");
	}
}
