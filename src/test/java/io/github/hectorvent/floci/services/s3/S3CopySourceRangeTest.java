package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service.CopySourceRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class S3CopySourceRangeTest {

    @TempDir
    Path tempDir;

    @Test
    void noHeaderCopiesTheWholeSource() {
        assertEquals(new CopySourceRange(0, 9), CopySourceRange.parse(null, 10));
        assertEquals(new CopySourceRange(0, 9), CopySourceRange.parse(" ", 10));
        assertEquals(0, CopySourceRange.parse(null, 0).length(), "an empty source copies as an empty part");
    }

    @Test
    void offsetsPastTwoGibibytesParseAsLongs() {
        CopySourceRange range = CopySourceRange.parse("bytes=3000000000-3000000099", 4_000_000_000L);

        assertEquals(3_000_000_000L, range.first());
        assertEquals(3_000_000_099L, range.last());
        assertEquals(100, range.length());
    }

    @Test
    void rangeMayEndOnTheLastByteOfTheSource() {
        assertEquals(new CopySourceRange(2, 9), CopySourceRange.parse("bytes=2-9", 10));
        assertEquals(new CopySourceRange(2, 5), CopySourceRange.parse("2-5", 10));
    }

    @Test
    void malformedReversedOrOutOfBoundsRangesAreRejected() {
        for (String header : List.of("bytes=5", "bytes=-5", "bytes=a-b", "bytes=", "bytes=6-5", "bytes=0-10", "bytes=10-12")) {
            AwsException error = assertThrows(AwsException.class, () -> CopySourceRange.parse(header, 10), header);
            assertEquals("InvalidArgument", error.getErrorCode(), header);
            assertEquals(400, error.getHttpStatus(), header);
            assertEquals("Invalid x-amz-copy-source-range: " + header, error.getMessage());
        }
        assertThrows(AwsException.class, () -> CopySourceRange.parse("bytes=0-0", 0), "an empty source has no bytes to range over");
    }

    @Test
    void readRangeSkipsToAnOffsetPastTwoGibibytes() throws IOException {
        CopySourceRange range = new CopySourceRange(2_500_000_000L, 2_500_000_015L);

        byte[] data = S3Service.readRange(patternStream(3L * 1024 * 1024 * 1024), range);

        assertEquals(16, data.length);
        for (int i = 0; i < data.length; i++) {
            assertEquals(patternByte(range.first() + i), data[i], "byte " + i);
        }
    }

    @Test
    void readRangeSkipsPastTwoGibibytesInTheFileStreamDiskObjectsAreReadThrough() throws IOException {
        long offset = 2_500_000_000L;
        byte[] written = new byte[16];
        for (int i = 0; i < written.length; i++) {
            written[i] = (byte) (i + 1);
        }
        // Sparse: only the bytes past 2.5 GB are written, so the file takes a single block on disk.
        Path file = tempDir.resolve("sparse-source");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            channel.write(ByteBuffer.wrap(written), offset);
        }

        byte[] data;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            data = S3Service.readRange(Channels.newInputStream(channel),
                    new CopySourceRange(offset, offset + written.length - 1));
        }

        assertArrayEquals(written, data);
    }

    @Test
    void readRangeFailsWhenTheSourceEndsBeforeTheRange() {
        assertThrows(IOException.class, () -> S3Service.readRange(patternStream(10), new CopySourceRange(5, 14)));
    }

    private static byte patternByte(long position) {
        return (byte) (position % 251);
    }

    /** {@code size} bytes where byte {@code p} is {@code p % 251}, skipped over without being produced. */
    private static InputStream patternStream(long size) {
        return new InputStream() {
            private long position;

            @Override
            public int read() {
                return position < size ? patternByte(position++) & 0xFF : -1;
            }

            @Override
            public long skip(long n) {
                long skipped = Math.max(0, Math.min(n, size - position));
                position += skipped;
                return skipped;
            }
        };
    }
}
