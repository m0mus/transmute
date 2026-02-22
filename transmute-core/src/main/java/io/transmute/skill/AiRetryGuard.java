package io.transmute.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

/**
 * Runaway-rewrite protection for AI-assisted skills.
 *
 * <p>Rejects a proposed file rewrite when:
 * <ul>
 *   <li>The line-delta exceeds {@code maxDeltaLines}</li>
 *   <li>The output size exceeds {@code maxOutputBytes}</li>
 *   <li>The exact output has been seen before in this guard instance (loop detection)</li>
 * </ul>
 */
public class AiRetryGuard {

    private final int maxDeltaLines;
    private final int maxOutputBytes;
    private final Set<String> seenHashes = new HashSet<>();

    public AiRetryGuard(int maxDeltaLines, int maxOutputBytes) {
        this.maxDeltaLines = maxDeltaLines;
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * Returns {@code true} when the proposed rewrite should be accepted.
     */
    public boolean accept(String before, String after) {
        if (after == null) {
            return false;
        }

        // Size check
        if (after.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
            return false;
        }

        // Delta check
        int beforeLines = countLines(before);
        int afterLines = countLines(after);
        if (Math.abs(afterLines - beforeLines) > maxDeltaLines) {
            return false;
        }

        // Loop detection
        var hash = sha256(after);
        if (seenHashes.contains(hash)) {
            return false;
        }
        seenHashes.add(hash);
        return true;
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return text.hashCode() + "";
        }
    }
}
