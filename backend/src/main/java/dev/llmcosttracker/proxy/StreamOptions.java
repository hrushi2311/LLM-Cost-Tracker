package dev.llmcosttracker.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Adds {@code "stream_options":{"include_usage":true}} to a streamed Chat Completions request that does not set
 * {@code stream_options} at all, so the final chunk carries usage. Only that field is added: the bytes are inserted
 * before the closing brace of the top-level object and everything else is left exactly as the client sent it.
 */
final class StreamOptions {

    static final String FIELD = "\"stream_options\":{\"include_usage\":true}";

    private StreamOptions() {}

    /** The body with the field added; the caller has already checked it is a JSON object without stream_options. */
    static byte[] injectIncludeUsage(byte[] body) {
        int close = lastNonWhitespace(body, body.length - 1);
        if (close < 0 || body[close] != '}') {
            return body; // not a JSON object: leave the request alone
        }
        int beforeClose = lastNonWhitespace(body, close - 1);
        boolean empty = beforeClose >= 0 && body[beforeClose] == '{';
        byte[] insert = ((empty ? "" : ",") + FIELD).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + insert.length];
        System.arraycopy(body, 0, out, 0, close);
        System.arraycopy(insert, 0, out, close, insert.length);
        System.arraycopy(body, close, out, close + insert.length, body.length - close);
        return out;
    }

    private static int lastNonWhitespace(byte[] b, int from) {
        int i = from;
        while (i >= 0 && (b[i] == ' ' || b[i] == '\n' || b[i] == '\r' || b[i] == '\t')) {
            i--;
        }
        return i;
    }
}
