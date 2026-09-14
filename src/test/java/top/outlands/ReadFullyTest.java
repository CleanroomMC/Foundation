package top.outlands;

import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.ActualClassLoader;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code ActualClassLoader.readFully}: it used to spin forever on a stream that
 * returns {@code 0} from a bulk read, and it used to turn any read failure into an empty byte array
 * (which was then cached as the class bytes and surfaced as a {@code ClassFormatError}). A stream
 * that never reports progress now gives up after a bounded number of single byte reads instead of
 * crawling through the whole class.
 */
class ReadFullyTest {

    private static final ExposedClassLoader LOADER = new ExposedClassLoader();
    private static final int BUFFER_SIZE = ActualClassLoader.BUFFER_SIZE;

    private static final class ExposedClassLoader extends ActualClassLoader {
        ExposedClassLoader() {
            super(new URL[0], ActualClassLoader.class.getClassLoader());
        }

        byte[] read(InputStream stream) throws IOException {
            return readFully(stream);
        }
    }

    /** Bulk reads always report "no progress", the data is only reachable through single byte reads. */
    private static final class ZeroForeverStream extends InputStream {
        /** fails the test instead of hanging it if a future implementation starts spinning again */
        private static final int SPIN_GUARD = 1_000_000;

        private final byte[] data;
        private int position;
        private int consecutiveZeros;
        private int singleByteReads;

        ZeroForeverStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            singleByteReads++;
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (++consecutiveZeros > SPIN_GUARD) {
                throw new IllegalStateException("bulk read returned 0 " + SPIN_GUARD + " times in a row: the loop is stuck");
            }
            return 0;
        }
    }

    /** Returns 0 a few times, then behaves like a normal stream. */
    private static final class ZeroThenNormalStream extends InputStream {
        private final byte[] data;
        private int position;
        private int zerosLeft;
        private int singleByteReads;

        ZeroThenNormalStream(byte[] data, int zeros) {
            this.data = data;
            this.zerosLeft = zeros;
        }

        @Override
        public int read() {
            singleByteReads++;
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0 || zerosLeft-- > 0) {
                return 0;
            }
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(len, data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            return count;
        }
    }

    /** Serves one byte per bulk read, so the loop has to accumulate many partial reads. */
    private static final class TinyChunksStream extends InputStream {
        private final byte[] data;
        private int position;

        TinyChunksStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            b[off] = data[position++];
            return 1;
        }
    }

    @Test
    void streamThatNeverReportsProgressFailsFastInsteadOfCrawling() {
        byte[] payload = payload(100_000);
        ZeroForeverStream stream = new ZeroForeverStream(payload);

        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IOException.class, () -> LOADER.read(stream)));

        assertTrue(failure.getMessage() != null && failure.getMessage().contains("no data"),
                "unexpected message: " + failure.getMessage());
        // it must give up instead of reading the whole class one byte at a time
        assertTrue(stream.singleByteReads <= 16,
                "read " + stream.singleByteReads + " bytes through the fallback before giving up");
        assertTrue(stream.singleByteReads < payload.length / 2);
    }

    @Test
    void aLimitedRunOfEmptyReadsIsTolerated() throws IOException {
        byte[] payload = payload(BUFFER_SIZE + 16);
        // 16 empty reads in a row are the documented limit, the data still has to arrive complete
        ZeroThenNormalStream stream = new ZeroThenNormalStream(payload, 16);

        assertArrayEquals(payload, LOADER.read(stream));
        assertEquals(16, stream.singleByteReads, "the empty reads must have gone through the fallback");
    }

    @Test
    void oneEmptyReadBeyondTheLimitFails() {
        ZeroThenNormalStream stream = new ZeroThenNormalStream(payload(64), 17);

        assertThrows(IOException.class, () -> LOADER.read(stream));
    }

    @Test
    void zeroReadsMixedWithNormalReadsAreHandled() {
        byte[] payload = payload(BUFFER_SIZE + 17);
        ZeroThenNormalStream stream = new ZeroThenNormalStream(payload, 5);

        assertArrayEquals(payload, assertTimeoutPreemptively(Duration.ofSeconds(10), () -> LOADER.read(stream)));
    }

    @Test
    void readFailureIsPropagatedInsteadOfReturningEmptyBytes() throws IOException {
        InputStream failing = new FilterInputStream(new ByteArrayInputStream(payload(64))) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated IO failure");
            }
        };

        assertThrows(IOException.class, () -> LOADER.read(failing),
                "a read failure must not be swallowed into an empty byte array");
    }

    @Test
    void readsTheWholeStream() throws IOException {
        for (int size : new int[]{0, 1, BUFFER_SIZE - 1, BUFFER_SIZE, BUFFER_SIZE + 1, 200_000}) {
            byte[] payload = payload(size);
            byte[] read = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> LOADER.read(new ByteArrayInputStream(payload)));
            assertEquals(size, read.length, "wrong length for a " + size + " byte stream");
            assertArrayEquals(payload, read, "wrong content for a " + size + " byte stream");
        }
    }

    @Test
    void manyPartialReadsAreAccumulated() throws IOException {
        byte[] payload = payload(BUFFER_SIZE * 3 + 7);
        assertArrayEquals(payload, LOADER.read(new TinyChunksStream(payload)));
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return Arrays.copyOf(data, size);
    }
}
