package dev.keneq.dsm.config;

import java.util.List;
import java.util.Locale;

public record TrackListConfig(
        String key,
        List<String> trackPatterns,
        PlayMode mode,
        double gapSeconds
) {
    public enum PlayMode {
        SEQUENCE,
        SHUFFLE;

        public static PlayMode fromString(String raw) {
            if (raw == null) {
                return SHUFFLE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "sequence" -> SEQUENCE;
                default -> SHUFFLE;
            };
        }
    }

    public boolean isEmpty() {
        return trackPatterns == null || trackPatterns.isEmpty();
    }
}