package com.geminiapp.chat;

/**
 * Замена android.util.Base64 (появился только в API 8).
 * Работает на любой версии Android, включая 1.0.
 */
public final class Base64Compat {

    private static final char[] ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
        // Вариант URL-safe тоже понимаем
        REVERSE['-'] = 62;
        REVERSE['_'] = 63;
    }

    private Base64Compat() {}

    /** Кодирует байты в Base64 одной строкой, без переносов. */
    public static String encode(byte[] data) {
        int len = data.length;
        StringBuilder sb = new StringBuilder(((len + 2) / 3) * 4);
        for (int i = 0; i < len; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < len) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < len) ? (data[i + 2] & 0xFF) : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append((i + 1 < len) ? ALPHABET[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append((i + 2 < len) ? ALPHABET[b2 & 63] : '=');
        }
        return sb.toString();
    }

    /** Декодирует Base64, пропуская переносы строк и пробелы. */
    public static byte[] decode(String s) {
        int n = s.length();
        byte[] out = new byte[(n * 3) / 4 + 3];
        int outLen = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c >= 128) continue;
            int v = REVERSE[c];
            if (v < 0) continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outLen++] = (byte) ((buffer >> bits) & 0xFF);
                buffer &= (1 << bits) - 1;
            }
        }
        byte[] result = new byte[outLen];
        System.arraycopy(out, 0, result, 0, outLen);
        return result;
    }
}
