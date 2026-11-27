package dev.llmcosttracker.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Splits an SSE byte stream into lines with bounded memory. Each line keeps at most its first {@link #HEAD} bytes
 * and its last {@link #TAIL} bytes; anything in between is counted but not stored. Memory is therefore a fixed
 * {@code HEAD + TAIL} bytes however long the stream or any single line is.
 *
 * <p>A Responses API {@code response.completed} event repeats the whole output in one line; its {@code type} and
 * {@code model} are near the start and {@code usage} near the end, which is why both ends are kept.
 */
public final class SseLineReader {

    public static final int HEAD = 64 * 1024;
    public static final int TAIL = 64 * 1024;

    /** One line. When {@link #truncated()}, only the first and last bytes are known; otherwise {@link #text()}. */
    public record Line(String head, String tail, long length, boolean truncated) {
        /** The whole line; only for lines that were not truncated. */
        public String text() {
            return head;
        }
    }

    private final Consumer<Line> listener;
    private final byte[] head = new byte[HEAD];
    private final byte[] tail = new byte[TAIL];
    private int headLength;
    private int tailStart;
    private int tailLength;
    private long lineLength;

    public SseLineReader(Consumer<Line> listener) {
        this.listener = listener;
    }

    public void feed(ByteBuffer bytes) {
        while (bytes.hasRemaining()) {
            byte b = bytes.get();
            if (b == '\n') {
                emit();
            } else {
                append(b);
            }
        }
    }

    public void feed(byte[] bytes) {
        feed(ByteBuffer.wrap(bytes));
    }

    /** Bytes currently held for the line in progress; never more than {@code HEAD + TAIL}. */
    public int retainedBytes() {
        return headLength + tailLength;
    }

    private void append(byte b) {
        lineLength++;
        if (headLength < HEAD) {
            head[headLength++] = b;
            return;
        }
        int pos = (tailStart + tailLength) % TAIL;
        tail[pos] = b;
        if (tailLength < TAIL) {
            tailLength++;
        } else {
            tailStart = (tailStart + 1) % TAIL;
        }
    }

    private void emit() {
        boolean truncated = lineLength > (long) HEAD + TAIL;
        byte[] tailBytes = new byte[tailLength];
        for (int i = 0; i < tailLength; i++) {
            tailBytes[i] = tail[(tailStart + i) % TAIL];
        }
        Line line;
        if (truncated) {
            line = new Line(new String(head, 0, headLength, StandardCharsets.UTF_8),
                    stripCr(new String(tailBytes, StandardCharsets.UTF_8)), lineLength, true);
        } else {
            // Decode the whole line at once so a multi-byte character at the head/tail boundary stays intact.
            byte[] all = new byte[headLength + tailLength];
            System.arraycopy(head, 0, all, 0, headLength);
            System.arraycopy(tailBytes, 0, all, headLength, tailLength);
            line = new Line(stripCr(new String(all, StandardCharsets.UTF_8)), "", lineLength, false);
        }
        headLength = 0;
        tailStart = 0;
        tailLength = 0;
        lineLength = 0;
        listener.accept(line);
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
