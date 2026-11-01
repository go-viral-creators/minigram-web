package com.silu.minigram.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Ogg-Opus muxer (RFC 7845). Takes raw Opus packets (what MediaCodec's Opus encoder emits) and writes a
 * playable .ogg file that Android's MediaPlayer, browsers and ffmpeg all understand.
 */
public final class OggOpusWriter {
    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            CRC[i] = r;
        }
    }

    private final OutputStream out;
    private final int serial;
    private int seq;
    private long granule;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final java.util.ArrayList<Integer> segs = new java.util.ArrayList<>();
    private int pendingPackets;
    private final int preSkip;
    private boolean closed;

    /** @param opusHead the OpusHead packet from the encoder's CSD, or null to write a default one. */
    public OggOpusWriter(OutputStream out, byte[] opusHead, int sampleRate) throws IOException {
        this.out = out;
        this.serial = new java.util.Random().nextInt();
        byte[] head = opusHead != null && opusHead.length >= 19 && startsWith(opusHead, "OpusHead") ? opusHead : defaultHead(sampleRate);
        this.preSkip = ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        writePage(0x02, 0, new byte[][]{head});                       // BOS
        byte[] vendor = "minigram".getBytes(StandardCharsets.UTF_8);
        ByteBuffer tags = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(vendor.length).put(vendor).putInt(0);
        writePage(0x00, 0, new byte[][]{tags.array()});
    }

    static byte[] defaultHead(int rate) {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 1)
         .putShort((short) 312).putInt(rate).putShort((short) 0).put((byte) 0);
        return b.array();
    }

    private static boolean startsWith(byte[] a, String s) {
        for (int i = 0; i < s.length(); i++) if (a[i] != (byte) s.charAt(i)) return false;
        return true;
    }

    /** @param samples48k number of 48 kHz samples this packet represents (e.g. 960 for 20 ms). */
    public void writePacket(byte[] packet, int samples48k) throws IOException {
        int lacing = packet.length / 255 + 1;
        if (segs.size() + lacing > 255 || pendingPackets >= 50) flush(0x00);
        int n = packet.length;
        while (n >= 255) { segs.add(255); n -= 255; }
        segs.add(n);
        pending.write(packet);
        pendingPackets++;
        granule += samples48k;
    }

    private void flush(int flags) throws IOException {
        if (segs.isEmpty() && flags == 0) return;
        long g = granule;
        if ((flags & 0x04) != 0 && finalSamples > 0) g = Math.min(granule, finalSamples + preSkip);   // RFC 7845: length = final granule - pre-skip
        writeRaw(flags, g, segs, pending.toByteArray());
        segs.clear(); pending.reset(); pendingPackets = 0;
    }

    /** Closes the stream; {@code totalSamples48k} is the exact number of PCM samples that were encoded (trims encoder padding). */
    public void close(long totalSamples48k) throws IOException {
        if (closed) return;
        finalSamples = totalSamples48k;
        close();
    }

    private long finalSamples;

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        flush(0x04);                                                   // EOS
        out.flush();
    }

    private void writePage(int flags, long granulePos, byte[][] packets) throws IOException {
        java.util.ArrayList<Integer> s = new java.util.ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : packets) {
            int n = p.length;
            while (n >= 255) { s.add(255); n -= 255; }
            s.add(n);
            body.write(p);
        }
        writeRaw(flags, granulePos, s, body.toByteArray());
    }

    private void writeRaw(int flags, long granulePos, java.util.List<Integer> segments, byte[] body) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(27 + segments.size()).order(ByteOrder.LITTLE_ENDIAN);
        h.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) flags)
         .putLong(granulePos).putInt(serial).putInt(seq++).putInt(0).put((byte) segments.size());
        for (int v : segments) h.put((byte) v);
        byte[] header = h.array();
        int crc = 0;
        for (byte b : header) crc = (crc << 8) ^ CRC[((crc >>> 24) & 0xff) ^ (b & 0xff)];
        for (byte b : body) crc = (crc << 8) ^ CRC[((crc >>> 24) & 0xff) ^ (b & 0xff)];
        ByteBuffer.wrap(header, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc);
        out.write(header);
        out.write(body);
    }
}
