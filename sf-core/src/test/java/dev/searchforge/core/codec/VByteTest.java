package dev.searchforge.core.codec;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VByteTest {

    @Property
    void roundTripsAnyNonNegativeInt(@ForAll @IntRange(min = 0) int value) throws IOException {
        byte[] bytes = VByte.encode(value);
        assertThat(VByte.read(ByteBuffer.wrap(bytes))).isEqualTo(value);
        assertThat(VByte.read(new DataInputStream(new ByteArrayInputStream(bytes))))
                .isEqualTo(value);
    }

    @Property
    void roundTripsSortedSequencesAsDeltas(
            @ForAll @Size(max = 200) List<@IntRange(min = 0, max = 1_000_000) Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VByte.writeDeltas(out, sorted, 0, sorted.length);
        int[] back = VByte.readDeltas(ByteBuffer.wrap(out.toByteArray()), sorted.length);
        assertThat(back).containsExactly(sorted);
    }

    @Test
    void usesOneByteForSmallValuesAndFiveForMax() {
        assertThat(VByte.encode(0)).hasSize(1);
        assertThat(VByte.encode(127)).hasSize(1);
        assertThat(VByte.encode(128)).hasSize(2);
        assertThat(VByte.encode(Integer.MAX_VALUE)).hasSize(5);
    }

    @Test
    void rejectsNegativeAndUnsortedInput() {
        assertThatThrownBy(() -> VByte.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        int[] unsorted = {5, 3};
        assertThatThrownBy(() -> VByte.writeDeltas(new ByteArrayOutputStream(), unsorted, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsCorruptStreams() {
        byte[] corrupt = new byte[8];
        Arrays.fill(corrupt, (byte) 0x7f);
        assertThatThrownBy(() -> VByte.read(ByteBuffer.wrap(corrupt)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VByte.read(new DataInputStream(new ByteArrayInputStream(corrupt))))
                .isInstanceOf(IOException.class);
    }
}
