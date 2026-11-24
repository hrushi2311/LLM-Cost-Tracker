package dev.llmcosttracker.proxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.springframework.core.io.buffer.DataBuffer;

/**
 * A side copy of a response body, used only to parse usage. Reads buffers without moving their read position, so
 * the bytes relayed to the client are untouched. Gives up (and frees memory) past {@code maxBytes}.
 */
final class BodyCopy {

    private final long maxBytes;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean overflowed;

    BodyCopy(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    void append(DataBuffer buffer) {
        if (overflowed) {
            return;
        }
        if ((long) out.size() + buffer.readableByteCount() > maxBytes) {
            overflowed = true;
            out.reset();
            return;
        }
        try (DataBuffer.ByteBufferIterator it = buffer.readableByteBuffers()) {
            while (it.hasNext()) {
                ByteBuffer view = it.next();
                byte[] chunk = new byte[view.remaining()];
                view.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }
    }

    boolean overflowed() {
        return overflowed;
    }

    byte[] bytes() {
        return out.toByteArray();
    }
}
