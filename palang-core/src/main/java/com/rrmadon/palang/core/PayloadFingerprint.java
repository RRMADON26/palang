package com.rrmadon.palang.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable fingerprint of a request payload, used to detect a client
 * reusing one idempotency key for two genuinely different requests.
 */
public final class PayloadFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private PayloadFingerprint() {
    }

    /**
     * Fingerprints a request body.
     *
     * @param body the raw request body; may be empty but not null
     * @return a lowercase hex SHA-256 digest
     */
    public static String of(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
        }
    }
}
