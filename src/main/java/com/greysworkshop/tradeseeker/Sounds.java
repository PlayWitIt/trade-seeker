package com.greysworkshop.tradeseeker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Little melodies for finds, purchases and the end of a list, made of note-block notes and played over a few ticks.
 * Pitches are in semitones above note-block F# (0..24 is the whole note-block range).
 */
public final class Sounds {
	/** How a find sounds; picked in Options. */
	public enum Style { CHIME, BELL, FANFARE, LEVEL_UP }

	private record Note(int at, SoundEvent sound, float pitch, float volume) {}

	private static final List<Note> queue = new ArrayList<>();
	private static int now;

	private Sounds() {}

	/** A wanted book turned up. Quieter for reminders. */
	public static void found(boolean reminder) {
		play(ModConfig.INSTANCE.foundSound, reminder ? 0.55f : 1f);
	}

	public static void play(Style style, float loudness) {
		switch (style) {
			case CHIME -> {
				// A bright rising arpeggio and a shimmer: "treasure!"
				note(0, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 12, loudness);
				note(3, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 16, loudness);
				note(6, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 19, loudness);
				note(9, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 24, loudness);
				note(9, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 12, loudness * 0.6f);
				add(13, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, loudness);
				add(16, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.9f, loudness * 0.5f);
			}
			case BELL -> {
				note(0, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 7, loudness);
				note(4, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 14, loudness);
				note(8, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 19, loudness);
				add(12, SoundEvents.BLOCK_BELL_USE, 1.4f, loudness * 0.5f);
			}
			case FANFARE -> {
				int[] run = {7, 9, 11, 14, 16, 19};
				for (int i = 0; i < run.length; i++) note(i * 2, SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value(), run[i], loudness);
				note(13, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 19, loudness);
				note(13, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 23, loudness * 0.8f);
			}
			case LEVEL_UP -> add(0, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, loudness);
		}
	}

	/** Bought: a coin pickup and a happy villager. */
	public static void bought() {
		add(0, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1f);
		note(2, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 19, 0.8f);
		add(5, SoundEvents.ENTITY_VILLAGER_YES, 1.0f, 0.9f);
	}

	/** The villager woke up and it's carrying on: a soft rising harp. */
	public static void morning() {
		note(0, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 12, 0.6f);
		note(3, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 16, 0.6f);
		note(6, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 19, 0.6f);
	}

	/** Every wanted book is in: the challenge-complete fanfare. */
	public static void allDone() {
		add(4, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
	}

	/** Something went wrong: two low notes, down. */
	public static void problem() {
		note(0, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 7, 1f);
		note(4, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 2, 1f);
	}

	private static void note(int at, SoundEvent sound, int semitones, float volume) {
		add(at, sound, (float) Math.pow(2, (semitones - 12) / 12.0), volume);
	}

	private static void add(int at, SoundEvent sound, float pitch, float volume) {
		queue.add(new Note(now + at, sound, pitch, volume));
	}

	public static void tick(MinecraftClient client) {
		now++;
		if (queue.isEmpty()) return;
		float master = ModConfig.INSTANCE.sound ? ModConfig.INSTANCE.volume / 100f : 0;
		for (int i = queue.size() - 1; i >= 0; i--) {
			Note n = queue.get(i);
			if (n.at > now) continue;
			queue.remove(i);
			if (master > 0) client.getSoundManager().play(PositionedSoundInstance.ui(n.sound, n.pitch, n.volume * master));
		}
	}
}
