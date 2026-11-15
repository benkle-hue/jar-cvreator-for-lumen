package dev.lumen.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.awt.Color;

/** One configurable value of a module. A single class keeps config + GUI code simple. */
public final class Setting {
	public enum Type { BOOL, NUMBER, MODE, TEXT, COLOR }

	public final String name;
	public final Type type;
	public double min, max, step = 1;
	public String[] modes = new String[0];
	public String hint = "";

	private boolean bool;
	private double number;
	private int index;
	private String text = "";

	private Setting(String name, Type type) {
		this.name = name;
		this.type = type;
	}

	public static Setting bool(String name, boolean def) {
		Setting s = new Setting(name, Type.BOOL);
		s.bool = def;
		return s;
	}

	public static Setting number(String name, double def, double min, double max, double step) {
		Setting s = new Setting(name, Type.NUMBER);
		s.min = min;
		s.max = max;
		s.step = step;
		s.number = def;
		return s;
	}

	public static Setting mode(String name, String def, String... modes) {
		Setting s = new Setting(name, Type.MODE);
		s.modes = modes;
		for (int i = 0; i < modes.length; i++) {
			if (modes[i].equals(def)) s.index = i;
		}
		return s;
	}

	public static Setting text(String name, String def, String hint) {
		Setting s = new Setting(name, Type.TEXT);
		s.text = def;
		s.hint = hint;
		return s;
	}

	/** A hue slider (0..1). */
	public static Setting hue(String name, double def) {
		Setting s = new Setting(name, Type.COLOR);
		s.min = 0;
		s.max = 1;
		s.number = def;
		return s;
	}

	public boolean getBool() { return bool; }
	public void setBool(boolean v) { bool = v; }

	public double getNumber() { return number; }

	public void setNumber(double v) {
		if (type == Type.NUMBER && step > 0) v = Math.round(v / step) * step;
		v = Math.max(min, Math.min(max, v));
		number = Math.round(v * 10000.0) / 10000.0;
	}

	public int getIndex() { return index; }
	public void setIndex(int i) { if (modes.length > 0) index = ((i % modes.length) + modes.length) % modes.length; }
	public void cycle(int dir) { setIndex(index + dir); }
	public String getMode() { return modes.length == 0 ? "" : modes[index]; }

	public String getText() { return text; }
	public void setText(String t) { text = t == null ? "" : t; }

	public double getHue() { return number; }

	/** Pastel-ish RGB (no alpha) from the hue slider. */
	public int getColor() { return Color.HSBtoRGB((float) number, 0.65f, 1f) & 0xFFFFFF; }

	public String display() {
		if (step >= 1) return String.valueOf((int) Math.round(number));
		return String.format("%.1f", number);
	}

	public JsonElement toJson() {
		return switch (type) {
			case BOOL -> new JsonPrimitive(bool);
			case NUMBER, COLOR -> new JsonPrimitive(number);
			case MODE -> new JsonPrimitive(getMode());
			case TEXT -> new JsonPrimitive(text);
		};
	}

	public void fromJson(JsonElement e) {
		if (e == null || e.isJsonNull()) return;
		try {
			switch (type) {
				case BOOL -> bool = e.getAsBoolean();
				case NUMBER, COLOR -> setNumber(e.getAsDouble());
				case MODE -> {
					String m = e.getAsString();
					for (int i = 0; i < modes.length; i++) {
						if (modes[i].equals(m)) index = i;
					}
				}
				case TEXT -> text = e.getAsString();
			}
		} catch (RuntimeException ignored) {
			// keep default on malformed config
		}
	}
}
