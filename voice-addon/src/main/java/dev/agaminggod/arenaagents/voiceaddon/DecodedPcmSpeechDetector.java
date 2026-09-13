package dev.agaminggod.arenaagents.voiceaddon;

/** Detects speech activity after Opus decoding, before an utterance enters STT. */
final class DecodedPcmSpeechDetector {
	/* Opus can encode frames as short as 2.5 ms at the 48 kHz capture rate. */
	private static final int MIN_VALID_OPUS_FRAME_SAMPLES = 120;
	/* Matches Simple Voice Chat's default -50 dB voice activation threshold closely. */
	private static final int MIN_SPEECH_PEAK = 96;
	private static final int MIN_SPEECH_RMS = 32;
	private static final int ACTIVE_SAMPLE_PERCENT = 1;
	private static final int MIN_ACTIVE_SAMPLES = 2;

	private DecodedPcmSpeechDetector() {
	}

	static boolean hasSpeech(short[] samples) {
		if (samples == null || samples.length == 0) return false;
		int peak = 0;
		int activeSamples = 0;
		long energy = 0L;
		for (short sample : samples) {
			int amplitude = Math.abs((int) sample);
			peak = Math.max(peak, amplitude);
			energy += (long) amplitude * amplitude;
			if (amplitude >= MIN_SPEECH_PEAK) activeSamples++;
		}
		if (samples.length < MIN_VALID_OPUS_FRAME_SAMPLES) {
			/* Alternate decoders may expose a partial frame. */
			return peak > 0;
		}
		long minimumEnergy = (long) MIN_SPEECH_RMS * MIN_SPEECH_RMS * samples.length;
		int minimumActiveSamples = Math.max(
				MIN_ACTIVE_SAMPLES,
				(samples.length * ACTIVE_SAMPLE_PERCENT + 99) / 100
		);
		return peak >= MIN_SPEECH_PEAK
				&& activeSamples >= minimumActiveSamples
				&& energy >= minimumEnergy;
	}
}
