package com.medicalrag.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Bounded basic framing checks only; not PDF parsing, image decoding or malware scanning. */
final class OriginalFormatValidator {
    private static final byte[] PNG = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private OriginalFormatValidator() { }
    static OriginalType validate(Path file, OriginalType expected) throws IOException {
        byte[] head;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { head = input.readNBytes(8); }
        OriginalType detected;
        if (head.length >= 5 && new String(head, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) detected = OriginalType.PDF;
        else if (Arrays.equals(head, PNG)) detected = OriginalType.PNG;
        else if (head.length >= 2 && (head[0] & 255) == 255 && (head[1] & 255) == 216) detected = OriginalType.JPEG;
        else throw new StorageException(StorageException.Code.UNSUPPORTED_FORMAT, "文件不是支持的 PDF、JPEG 或 PNG 原件");
        if (detected != expected) throw new StorageException(StorageException.Code.FORMAT_MISMATCH, "扩展名、预期类型与文件实际内容不一致");
        try {
            switch (detected) { case PDF -> pdf(file); case PNG -> png(file); case JPEG -> jpeg(file); }
        } catch (EOFException exception) { throw damaged(); }
        return detected;
    }
    private static StorageException damaged() { return new StorageException(StorageException.Code.DAMAGED_FILE, "文件基础结构损坏或不完整，未保存为正式原件"); }
    private static String block(FileChannel channel, long position, int maximum) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate((int)Math.min(maximum, channel.size() - position));
        channel.position(position);
        while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        return new String(bytes.array(), StandardCharsets.ISO_8859_1);
    }
    private static void pdf(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 32 || !Pattern.compile("^%PDF-(?:1\\.[0-7]|2\\.0)\\s").matcher(block(channel, 0, 16)).find()) throw damaged();
            String tail = block(channel, Math.max(0, size - 4096), 4096);
            var marker = Pattern.compile("startxref\\s+([0-9]+)\\s+%%EOF\\s*$").matcher(tail);
            if (!marker.find()) throw damaged();
            long offset;
            try { offset = Long.parseLong(marker.group(1)); } catch (NumberFormatException exception) { throw damaged(); }
            if (offset <= 0 || offset >= size) throw damaged();
            String xref = block(channel, offset, 4096);
            boolean classic = Pattern.compile("^xref\\s").matcher(xref).find();
            boolean stream = Pattern.compile("^[0-9]+\\s+[0-9]+\\s+obj\\s").matcher(xref).find()
                    && Pattern.compile("/Type\\s*/XRef\\b").matcher(xref).find();
            if ((!classic && !stream) || !Pattern.compile("/Root\\s+[0-9]+\\s+[0-9]+\\s+R\\b").matcher(xref + tail).find()) throw damaged();
        }
    }
    private static void png(Path file) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)))) {
            in.skipNBytes(8);
            long consumed = 8, size = Files.size(file);
            boolean header = false, data = false, palette = false, dataEnded = false;
            int colorType = -1;
            byte[] buffer = new byte[8192];
            while (consumed < size) {
                long length = Integer.toUnsignedLong(in.readInt());
                byte[] typeBytes = in.readNBytes(4);
                if (typeBytes.length != 4) throw damaged();
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                consumed += 8;
                if (!type.matches("[A-Za-z]{4}") || !Character.isUpperCase(type.charAt(2)) || length > size - consumed - 4) throw damaged();
                if (!header && !type.equals("IHDR")) throw damaged();
                if (Character.isUpperCase(type.charAt(0)) && !Set.of("IHDR", "PLTE", "IDAT", "IEND").contains(type)) throw damaged();
                CRC32 crc = new CRC32(); crc.update(typeBytes);
                byte[] ihdr = null;
                if (type.equals("IHDR")) {
                    if (header || length != 13) throw damaged();
                    ihdr = in.readNBytes(13);
                    if (ihdr.length != 13) throw damaged();
                    crc.update(ihdr);
                } else {
                    for (long remaining = length; remaining > 0;) {
                        int count = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (count < 0) throw damaged();
                        crc.update(buffer, 0, count); remaining -= count;
                    }
                }
                if (crc.getValue() != Integer.toUnsignedLong(in.readInt())) throw damaged();
                consumed += length + 4;
                if (ihdr != null) {
                    ByteBuffer h = ByteBuffer.wrap(ihdr);
                    if (h.getInt() <= 0 || h.getInt() <= 0) throw damaged();
                    int depth = h.get() & 255; colorType = h.get() & 255;
                    boolean legal = switch (colorType) {
                        case 0 -> Set.of(1, 2, 4, 8, 16).contains(depth);
                        case 2, 4, 6 -> depth == 8 || depth == 16;
                        case 3 -> Set.of(1, 2, 4, 8).contains(depth);
                        default -> false;
                    };
                    if (!legal || h.get() != 0 || h.get() != 0 || (h.get() & 255) > 1) throw damaged();
                    header = true;
                } else if (type.equals("PLTE")) {
                    if (palette || data || length == 0 || length > 768 || length % 3 != 0) throw damaged();
                    palette = true;
                } else if (type.equals("IDAT")) {
                    if (dataEnded || (colorType == 3 && !palette)) throw damaged();
                    if (length > 0) data = true;
                } else if (type.equals("IEND")) {
                    if (length != 0 || !data || consumed != size) throw damaged();
                    return;
                } else if (data) dataEnded = true;
            }
            throw damaged();
        }
    }
    private static void jpeg(Path file) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)))) {
            if (in.readUnsignedShort() != 0xffd8) throw damaged();
            boolean frame = false, scan = false, entropy = false;
            int pending = -1;
            while (true) {
                int marker;
                if (pending >= 0) { marker = pending; pending = -1; }
                else {
                    if (in.readUnsignedByte() != 255) throw damaged();
                    do { marker = in.readUnsignedByte(); } while (marker == 255);
                }
                if (marker == 0xd9) {
                    if (!frame || !scan || !entropy || in.read() != -1) throw damaged();
                    return;
                }
                if (marker == 0 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) throw damaged();
                if (marker == 1) continue;
                int payload = in.readUnsignedShort() - 2;
                if (payload < 0) throw damaged();
                if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                    if (payload < 6) throw damaged();
                    int precision = in.readUnsignedByte(); int height = in.readUnsignedShort(); int width = in.readUnsignedShort();
                    int components = in.readUnsignedByte();
                    if (!Set.of(8, 12, 16).contains(precision) || height == 0 || width == 0 || components == 0 || payload != 6 + 3 * components) throw damaged();
                    in.skipNBytes(payload - 6); frame = true;
                } else if (marker == 0xda) {
                    if (!frame || payload < 4) throw damaged();
                    int components = in.readUnsignedByte();
                    if (components == 0 || payload != 4 + 2 * components) throw damaged();
                    in.skipNBytes(payload - 1); scan = true;
                    while (true) {
                        int value = in.readUnsignedByte();
                        if (value != 255) { entropy = true; continue; }
                        int next;
                        do { next = in.readUnsignedByte(); } while (next == 255);
                        if (next == 0) { entropy = true; continue; }
                        if (next >= 0xd0 && next <= 0xd7) continue;
                        pending = next; break;
                    }
                } else in.skipNBytes(payload);
            }
        }
    }
}
