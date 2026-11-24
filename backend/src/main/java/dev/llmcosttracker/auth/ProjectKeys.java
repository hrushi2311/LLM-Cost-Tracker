package dev.llmcosttracker.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Project key format: {@code trk_} + base62 of 32 random bytes. Only the SHA-256 hash and last 4 chars are stored. */
public final class ProjectKeys {

    public static final String PREFIX = "trk_";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProjectKeys() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + base62(bytes);
    }

    static String base62(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder out = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] qr = value.divideAndRemainder(base);
            out.append(BASE62.charAt(qr[1].intValue()));
            value = qr[0];
        }
        return out.isEmpty() ? "0" : out.reverse().toString();
    }

    /** Lowercase hex SHA-256 of the full key. */
    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String last4(String key) {
        return key.substring(Math.max(0, key.length() - 4));
    }
}
