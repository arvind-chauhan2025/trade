package com.example.demo.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Minimal RFC 6238 TOTP generator (compatible with Google Authenticator style
 * secrets) so we don't need an extra dependency just to log in to Angel One.
 */
public final class TotpGenerator {

    private static final int DIGITS = 6;
    private static final long TIME_STEP_SECONDS = 30;

    private TotpGenerator() {
    }

    /** Generates the current TOTP code for the given Base32 secret. */
    public static String now(String base32Secret) {
        byte[] key = base32Decode(base32Secret);
        long counter = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        return generate(key, counter);
    }

    private static String generate(byte[] key, long counter) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate TOTP code", e);
        }
    }

    private static byte[] base32Decode(String base32) {
        String cleaned = base32.trim().replace("=", "").toUpperCase();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[cleaned.length() * 5 / 8];
        int index = 0;
        for (char c : cleaned.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}

