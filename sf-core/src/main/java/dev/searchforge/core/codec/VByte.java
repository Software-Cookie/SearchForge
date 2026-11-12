package dev.searchforge.core.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

// Variable-Byte integer codec (Manning, Raghavan, Schutze, IIR ch. 5):
// 7 payload bits per byte, low-order group first; the high bit marks the LAST byte.
// Small numbers (typical d-gaps) take one byte instead of four.
public final class VByte {

    private VByte() {
    }

    public static void write(OutputStream out, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("VByte encodes non-negative ints only: " + value);
        }
        try {
            while (value >= 0x80) {
                out.write(value & 0x7f);
                value >>>= 7;
            }
            out.write(value | 0x80);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length + 8);
        for (int v : values) {
            write(out, v);
        }
        return out.toByteArray();
    }

    // Reads one value starting at the buffer's current position and advances it.
    public static int read(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = buf.get() & 0xff;
            if (shift > 28) {
                throw new IllegalStateException("Corrupt VByte stream");
            }
            if ((b & 0x80) != 0) {
                return value | ((b & 0x7f) << shift);
            }
            value |= b << shift;
            shift += 7;
        }
    }

    public static int read(DataInput in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            if (shift > 28) {
                throw new IOException("Corrupt VByte stream");
            }
            if ((b & 0x80) != 0) {
                return value | ((b & 0x7f) << shift);
            }
            value |= b << shift;
            shift += 7;
        }
    }

    // Writes a sorted int sequence as d-gaps: first value, then differences.
    public static void writeDeltas(OutputStream out, int[] sorted, int from, int to) {
        int prev = 0;
        for (int i = from; i < to; i++) {
            int gap = sorted[i] - prev;
            if (gap < 0) {
                throw new IllegalArgumentException("Sequence must be non-decreasing");
            }
            write(out, gap);
            prev = sorted[i];
        }
    }

    public static int[] readDeltas(ByteBuffer buf, int count) {
        int[] out = new int[count];
        int prev = 0;
        for (int i = 0; i < count; i++) {
            prev += read(buf);
            out[i] = prev;
        }
        return out;
    }
}
