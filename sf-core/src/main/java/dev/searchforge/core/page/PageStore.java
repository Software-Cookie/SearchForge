package dev.searchforge.core.page;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;

// Append-only page log: [int length][int crc32][payload] per record.
// A crash can leave a torn last record; open() truncates it away so the crawl can resume.
public final class PageStore implements Closeable {

    public static final String FILE_NAME = "pages.bin";
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private final DataOutputStream out;
    private long count;

    private PageStore(DataOutputStream out, long count) {
        this.out = out;
        this.count = count;
    }

    // Opens (or creates) the store in dir for appending, dropping any torn tail.
    public static PageStore openForAppend(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        long[] valid = {0, 0};
        if (Files.exists(file)) {
            valid = scanValid(file);
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(valid[0]);
            }
        }
        OutputStream os = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        return new PageStore(new DataOutputStream(new BufferedOutputStream(os, 1 << 16)), valid[1]);
    }

    public synchronized void append(PageRecord page) throws IOException {
        byte[] payload = encode(page);
        CRC32 crc = new CRC32();
        crc.update(payload);
        out.writeInt(payload.length);
        out.writeInt((int) crc.getValue());
        out.write(payload);
        count++;
    }

    public synchronized long count() {
        return count;
    }

    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }

    // Streams every valid record in dir to the consumer. Stops at a torn tail.
    public static long readAll(Path dir, Consumer<PageRecord> consumer) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return 0;
        }
        long n = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            byte[] payload;
            while ((payload = nextPayload(in)) != null) {
                consumer.accept(decode(payload));
                n++;
            }
        }
        return n;
    }

    public static List<PageRecord> readAll(Path dir) throws IOException {
        List<PageRecord> pages = new ArrayList<>();
        readAll(dir, pages::add);
        return pages;
    }

    private static long[] scanValid(Path file) throws IOException {
        long offset = 0;
        long n = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            byte[] payload;
            while ((payload = nextPayload(in)) != null) {
                offset += 8 + payload.length;
                n++;
            }
        }
        return new long[] {offset, n};
    }

    // Returns the next intact payload, or null at EOF / torn or corrupt record.
    private static byte[] nextPayload(DataInputStream in) throws IOException {
        try {
            int len = in.readInt();
            int crcValue = in.readInt();
            if (len < 0 || len > MAX_RECORD_BYTES) {
                return null;
            }
            byte[] payload = in.readNBytes(len);
            if (payload.length != len) {
                return null;
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            return (int) crc.getValue() == crcValue ? payload : null;
        } catch (EOFException e) {
            return null;
        }
    }

    static byte[] encode(PageRecord p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + p.body().length());
        DataOutputStream d = new DataOutputStream(bytes);
        writeString(d, p.url());
        writeString(d, p.title());
        writeString(d, p.headings());
        writeString(d, p.body());
        d.writeInt(p.outlinks().size());
        for (String link : p.outlinks()) {
            writeString(d, link);
        }
        d.writeLong(p.fetchedAtEpochMs());
        d.flush();
        return bytes.toByteArray();
    }

    static PageRecord decode(byte[] payload) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        String url = readString(d);
        String title = readString(d);
        String headings = readString(d);
        String body = readString(d);
        int links = d.readInt();
        List<String> outlinks = new ArrayList<>(Math.max(0, Math.min(links, 10_000)));
        for (int i = 0; i < links; i++) {
            outlinks.add(readString(d));
        }
        long fetchedAt = d.readLong();
        return new PageRecord(url, title, headings, body, outlinks, fetchedAt);
    }

    private static void writeString(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length);
        d.write(b);
    }

    private static String readString(DataInputStream d) throws IOException {
        int len = d.readInt();
        if (len < 0 || len > MAX_RECORD_BYTES) {
            throw new IOException("Corrupt string length " + len);
        }
        return new String(readExactly(d, len), StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new EOFException();
        }
        return b;
    }
}
