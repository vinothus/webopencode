package com.pawwork.opencode.stt;

/**
 * The result of one transcription.
 *
 * @param text        the transcript (empty for silence)
 * @param language    the language that was spoken — {@code en} or {@code ta}
 * @param model       the Whisper size that produced it
 * @param duration    the clip's length in seconds
 * @param sampleRate  the rate the samples were decoded at (always 16 kHz)
 * @param elapsedMs   wall-clock time for the whole request
 * @param inferenceMs wall-clock time spent inside the decoder
 */
public record SttTranscription(String text, String language, String model,
                               double duration, int sampleRate,
                               long elapsedMs, long inferenceMs) {
}
