package com.example.common.spool;

import org.springframework.http.HttpHeaders;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binary payload metadata codecs.
 *
 * HKP1 (V1): legacy header encoding without explicit value counts per header name (best-effort decoding).
 * HKP2 (V2): deterministic header encoding with headerCount and valueCount.
 */
public final class Payload {

    private Payload() {}

    // ----------------------------- Guards -----------------------------

    // These limits protect against corrupted / malicious segments.
    // Tune as needed based on expected production traffic.
    private static final int MAX_UTF8_LEN = 128 * 1024;          // 128 KB per string token
    private static final int MAX_HEADERS_SECTION_LEN = 512 * 1024; // 512 KB for headers blob (V1)
    private static final int MAX_HEADERS_COUNT = 1024;           // V2 header count
    private static final int MAX_HEADER_VALUES = 128;            // V2 values per header

    // ----------------------------- V1 -----------------------------

    public static final class PayloadV1 {
        public static final int MAGIC = 0x484B5031; // 'H''K''P''1'

        public static ByteBuffer encodeMeta(
                String method,
                String scheme,
                byte[] remoteIp,
                String host,
                String path,
                String queryRawNoQuestionMark,
                String machine,
                HttpHeaders headers,
                String originalIpFinal,
                long bodyLen
        ) {
            if (bodyLen < 0) throw new IllegalArgumentException("bodyLen must be >= 0");

            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);

            writeInt32(out, MAGIC);
            writeInt16(out, 0); // flags

            writeU8(out, methodToByte(method));
            writeU8(out, schemeToByte(scheme));

            if (remoteIp == null) {
                writeU8(out, 0);
            } else {
                if (remoteIp.length > 255) throw new IllegalArgumentException("remoteIp too long: " + remoteIp.length);
                writeU8(out, remoteIp.length);
                out.writeBytes(remoteIp);
            }

            writeVarUtf8(out, host);
            writeVarUtf8(out, path);
            writeVarUtf8(out, queryRawNoQuestionMark);
            writeVarUtf8(out, machine);

            // V1 headers: sequence of tokens: name, value, value, ..., nextName, value, ...
            // Note: no explicit value counts; decoding is heuristic.
            ByteArrayOutputStream hb = new ByteArrayOutputStream(512);

            headers.forEach((name, values) -> {
                writeVarAsciiLower(hb, name == null ? "" : name);
                for (String v : values) {
                    writeVarUtf8(hb, v == null ? "" : v);
                }
            });

            if (originalIpFinal != null) {
                writeVarAsciiLower(hb, "x-hookdeck-original-ip");
                writeVarUtf8(hb, originalIpFinal);
            }

            byte[] headerBytes = hb.toByteArray();
            writeVarint(out, headerBytes.length);
            out.writeBytes(headerBytes);

            writeVarint64(out, bodyLen);

            return ByteBuffer.wrap(out.toByteArray());
        }

        /**
         * Decodes meta written by encodeMeta().
         * Returns a JSON-friendly Map that can be serialized with Jackson.
         */
        public static Map<String, Object> decodeMeta(ByteBuffer meta) throws IOException {
            Objects.requireNonNull(meta, "meta");

            ByteBuffer b = meta.asReadOnlyBuffer();
            b.order(ByteOrder.BIG_ENDIAN);

            return decodeMetaInternal(b);
        }

        /**
         * Decodes meta and also returns how many bytes were consumed from the provided buffer.
         * This is useful when meta is embedded at the start of a larger payload, and you need to know where body starts.
         */
        public static DecodedMeta decodeMetaWithSize(ByteBuffer metaAtPosition0) throws IOException {
            Objects.requireNonNull(metaAtPosition0, "metaAtPosition0");

            ByteBuffer b = metaAtPosition0.asReadOnlyBuffer();
            b.order(ByteOrder.BIG_ENDIAN);

            int start = b.position();
            Map<String, Object> m = decodeMetaInternal(b);
            int end = b.position();

            return new DecodedMeta(m, end - start);
        }

        private static Map<String, Object> decodeMetaInternal(ByteBuffer b) throws IOException {
            int magic = readInt32(b);
            if (magic != MAGIC) {
                throw new IOException("bad meta magic: 0x" + Integer.toHexString(magic));
            }

            int flags = readInt16(b);
            int methodCode = readU8(b);
            int schemeCode = readU8(b);

            int ipLen = readU8(b);
            byte[] ip = null;
            if (ipLen > 0) {
                if (b.remaining() < ipLen) throw new EOFException("remoteIp truncated");
                ip = new byte[ipLen];
                b.get(ip);
            }

            String host = readVarUtf8(b);
            String path = readVarUtf8(b);
            String query = readVarUtf8(b);
            String machine = readVarUtf8(b);

            int headersBytesLen = readVarint(b);
            if (headersBytesLen < 0) throw new IOException("negative headersBytesLen");
            if (headersBytesLen > MAX_HEADERS_SECTION_LEN) throw new IOException("headers section too large: " + headersBytesLen);
            if (b.remaining() < headersBytesLen) throw new EOFException("headers section truncated");

            ByteBuffer hb = slice(b, headersBytesLen);
            Map<String, Object> headers = decodeHeadersBestEffort(hb);

            long bodyLen = readVarint64(b);
            if (bodyLen < 0) throw new IOException("negative bodyLen");

            Map<String, Object> out = new LinkedHashMap<>(16);
            out.put("magic", "HKP1");
            out.put("flags", flags);
            out.put("method", byteToMethod(methodCode));
            out.put("scheme", byteToScheme(schemeCode));
            out.put("remote_ip", ip == null ? null : ipToString(ip));
            out.put("host", host);
            out.put("path", path);
            out.put("query", query);
            out.put("machine", machine);
            out.put("headers", headers);
            out.put("body_len", bodyLen);

            return out;
        }

        // ----------------- header decoding -----------------

        /**
         * IMPORTANT:
         * V1 header encoding does NOT include value count per header name.
         * So decoding uses a heuristic: token is a "name" if it looks like ascii header name.
         */
        private static Map<String, Object> decodeHeadersBestEffort(ByteBuffer hb) throws IOException {
            Map<String, List<String>> tmp = new LinkedHashMap<>();
            String currentName = null;

            while (hb.hasRemaining()) {
                String token = readVarUtf8(hb);

                if (currentName == null) {
                    currentName = token;
                    tmp.computeIfAbsent(currentName, k -> new ArrayList<>());
                    continue;
                }

                tmp.get(currentName).add(token);

                if (hb.hasRemaining()) {
                    hb.mark();
                    String next = readVarUtf8(hb);
                    boolean looksLikeName = looksLikeHeaderName(next);
                    hb.reset();

                    if (looksLikeName) {
                        currentName = null;
                    }
                }
            }

            Map<String, Object> headers = new LinkedHashMap<>();
            for (var e : tmp.entrySet()) {
                List<String> vals = e.getValue();
                if (vals.isEmpty()) headers.put(e.getKey(), "");
                else if (vals.size() == 1) headers.put(e.getKey(), vals.get(0));
                else headers.put(e.getKey(), vals);
            }
            return headers;
        }

        private static boolean looksLikeHeaderName(String s) {
            if (s == null || s.isEmpty()) return false;
            int n = s.length();
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z')
                             || (c >= '0' && c <= '9')
                             || c == '-' || c == '_';
                if (!ok) return false;
            }
            return n >= 2;
        }

        private static String ipToString(byte[] ipBytes) {
            try {
                return InetAddress.getByAddress(ipBytes).getHostAddress();
            } catch (Exception e) {
                return null;
            }
        }

        // ----------------- codec helpers -----------------

        private static ByteBuffer slice(ByteBuffer b, int len) {
            ByteBuffer s = b.slice();
            s.limit(len);
            b.position(b.position() + len);
            return s;
        }

        private static byte methodToByte(String m) {
            if (m == null) return 0;
            return switch (m) {
                case "GET" -> 1;
                case "POST" -> 2;
                case "PUT" -> 3;
                case "PATCH" -> 4;
                case "DELETE" -> 5;
                case "HEAD" -> 6;
                case "OPTIONS" -> 7;
                default -> 0;
            };
        }

        private static byte schemeToByte(String s) {
            if (s == null) return 0;
            return switch (s) {
                case "http" -> 1;
                case "https" -> 2;
                default -> 0;
            };
        }

        private static String byteToMethod(int code) {
            return switch (code) {
                case 1 -> "GET";
                case 2 -> "POST";
                case 3 -> "PUT";
                case 4 -> "PATCH";
                case 5 -> "DELETE";
                case 6 -> "HEAD";
                case 7 -> "OPTIONS";
                default -> "";
            };
        }

        private static String byteToScheme(int code) {
            return switch (code) {
                case 1 -> "http";
                case 2 -> "https";
                default -> "";
            };
        }

        private static void writeVarUtf8(ByteArrayOutputStream out, String s) {
            String x = (s == null) ? "" : s;
            byte[] b = x.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, b.length);
            out.writeBytes(b);
        }

        private static void writeVarAsciiLower(ByteArrayOutputStream out, String s) {
            if (s == null || s.isEmpty()) {
                writeVarint(out, 0);
                return;
            }

            int n = s.length();
            boolean ascii = true;
            for (int i = 0; i < n; i++) {
                if (s.charAt(i) > 0x7F) { ascii = false; break; }
            }
            if (!ascii) {
                writeVarUtf8(out, s.toLowerCase(Locale.ROOT));
                return;
            }

            writeVarint(out, n);
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
                out.write((byte) c);
            }
        }

        private static void writeU8(ByteArrayOutputStream out, int v) {
            out.write(v & 0xFF);
        }

        private static void writeInt16(ByteArrayOutputStream out, int v) {
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private static void writeInt32(ByteArrayOutputStream out, int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private static void writeVarint(ByteArrayOutputStream out, int v) {
            int x = v;
            while ((x & ~0x7F) != 0) {
                out.write((x & 0x7F) | 0x80);
                x >>>= 7;
            }
            out.write(x);
        }

        private static void writeVarint64(ByteArrayOutputStream out, long v) {
            long x = v;
            while ((x & ~0x7FL) != 0) {
                out.write((int) (x & 0x7F) | 0x80);
                x >>>= 7;
            }
            out.write((int) x);
        }

        private static int readU8(ByteBuffer b) throws EOFException {
            if (!b.hasRemaining()) throw new EOFException();
            return b.get() & 0xFF;
        }

        private static int readInt16(ByteBuffer b) throws EOFException {
            if (b.remaining() < 2) throw new EOFException();
            return ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
        }

        private static int readInt32(ByteBuffer b) throws EOFException {
            if (b.remaining() < 4) throw new EOFException();
            return ((b.get() & 0xFF) << 24)
                   | ((b.get() & 0xFF) << 16)
                   | ((b.get() & 0xFF) << 8)
                   | (b.get() & 0xFF);
        }

        private static int readVarint(ByteBuffer b) throws EOFException, IOException {
            int shift = 0;
            int result = 0;
            while (shift < 32) {
                int x = readU8(b);
                result |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("varint too long");
        }

        private static long readVarint64(ByteBuffer b) throws EOFException, IOException {
            int shift = 0;
            long result = 0;
            while (shift < 64) {
                int x = readU8(b);
                result |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("varint64 too long");
        }

        private static String readVarUtf8(ByteBuffer b) throws EOFException, IOException {
            int len = readVarint(b);
            if (len < 0) throw new IOException("negative utf8 len");
            if (len > MAX_UTF8_LEN) throw new IOException("utf8 token too large: " + len);
            if (b.remaining() < len) throw new EOFException();
            byte[] data = new byte[len];
            b.get(data);
            return new String(data, StandardCharsets.UTF_8);
        }

        private PayloadV1() {}
    }

    // ----------------------------- V2 (recommended) -----------------------------

    public static final class PayloadV2 {
        public static final int MAGIC = 0x484B5032; // 'H''K''P''2'

        public static ByteBuffer encodeMeta(
                String method,
                String scheme,
                byte[] remoteIp,
                String host,
                String path,
                String queryRawNoQuestionMark,
                String machine,
                HttpHeaders headers,
                String originalIpFinal,
                long bodyLen
        ) {
            if (bodyLen < 0) throw new IllegalArgumentException("bodyLen must be >= 0");

            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);

            writeInt32(out, MAGIC);
            writeInt16(out, 0); // flags

            writeU8(out, methodToByte(method));
            writeU8(out, schemeToByte(scheme));

            if (remoteIp == null) {
                writeU8(out, 0);
            } else {
                if (remoteIp.length > 255) throw new IllegalArgumentException("remoteIp too long: " + remoteIp.length);
                writeU8(out, remoteIp.length);
                out.writeBytes(remoteIp);
            }

            writeVarUtf8(out, host);
            writeVarUtf8(out, path);
            writeVarUtf8(out, queryRawNoQuestionMark);
            writeVarUtf8(out, machine);

            // Deterministic headers:
            // varint headerCount
            // repeat headerCount times:
            //   varAsciiLower(name)
            //   varint valueCount
            //   valueCount * varUtf8(value)
            //
            // Note: We also optionally append x-hookdeck-original-ip as an extra header.
            List<Map.Entry<String, List<String>>> hs = new ArrayList<>();
            headers.forEach((name, values) -> {
                String n = (name == null) ? "" : name;
                List<String> vs = new ArrayList<>(values.size());
                for (String v : values) vs.add(v == null ? "" : v);
                hs.add(new AbstractMap.SimpleEntry<>(n, vs));
            });
            if (originalIpFinal != null) {
                hs.add(new AbstractMap.SimpleEntry<>("x-hookdeck-original-ip", List.of(originalIpFinal)));
            }

            if (hs.size() > MAX_HEADERS_COUNT) throw new IllegalArgumentException("too many headers: " + hs.size());

            writeVarint(out, hs.size());
            for (Map.Entry<String, List<String>> e : hs) {
                writeVarAsciiLower(out, e.getKey());
                List<String> vs = e.getValue();
                if (vs.size() > MAX_HEADER_VALUES) throw new IllegalArgumentException("too many header values for " + e.getKey() + ": " + vs.size());
                writeVarint(out, vs.size());
                for (String v : vs) writeVarUtf8(out, v);
            }

            writeVarint64(out, bodyLen);

            return ByteBuffer.wrap(out.toByteArray());
        }

        public static Map<String, Object> decodeMeta(ByteBuffer meta) throws IOException {
            Objects.requireNonNull(meta, "meta");

            ByteBuffer b = meta.asReadOnlyBuffer();
            b.order(ByteOrder.BIG_ENDIAN);

            return decodeMetaInternal(b);
        }

        public static DecodedMeta decodeMetaWithSize(ByteBuffer metaAtPosition0) throws IOException {
            Objects.requireNonNull(metaAtPosition0, "metaAtPosition0");

            ByteBuffer b = metaAtPosition0.asReadOnlyBuffer();
            b.order(ByteOrder.BIG_ENDIAN);

            int start = b.position();
            Map<String, Object> m = decodeMetaInternal(b);
            int end = b.position();

            return new DecodedMeta(m, end - start);
        }

        private static Map<String, Object> decodeMetaInternal(ByteBuffer b) throws IOException {
            int magic = readInt32(b);
            if (magic != MAGIC) {
                throw new IOException("bad meta magic: 0x" + Integer.toHexString(magic));
            }

            int flags = readInt16(b);
            int methodCode = readU8(b);
            int schemeCode = readU8(b);

            int ipLen = readU8(b);
            byte[] ip = null;
            if (ipLen > 0) {
                if (b.remaining() < ipLen) throw new EOFException("remoteIp truncated");
                ip = new byte[ipLen];
                b.get(ip);
            }

            String host = readVarUtf8(b);
            String path = readVarUtf8(b);
            String query = readVarUtf8(b);
            String machine = readVarUtf8(b);

            int headerCount = readVarint(b);
            if (headerCount < 0) throw new IOException("negative headerCount");
            if (headerCount > MAX_HEADERS_COUNT) throw new IOException("too many headers: " + headerCount);

            Map<String, Object> headers = new LinkedHashMap<>(headerCount);
            for (int i = 0; i < headerCount; i++) {
                String name = readVarUtf8(b);
                int valueCount = readVarint(b);
                if (valueCount < 0) throw new IOException("negative valueCount");
                if (valueCount > MAX_HEADER_VALUES) throw new IOException("too many header values: " + valueCount);

                if (valueCount == 0) {
                    headers.put(name, "");
                } else if (valueCount == 1) {
                    headers.put(name, readVarUtf8(b));
                } else {
                    List<String> vs = new ArrayList<>(valueCount);
                    for (int j = 0; j < valueCount; j++) vs.add(readVarUtf8(b));
                    headers.put(name, vs);
                }
            }

            long bodyLen = readVarint64(b);
            if (bodyLen < 0) throw new IOException("negative bodyLen");

            Map<String, Object> out = new LinkedHashMap<>(16);
            out.put("magic", "HKP2");
            out.put("flags", flags);
            out.put("method", byteToMethod(methodCode));
            out.put("scheme", byteToScheme(schemeCode));
            out.put("remote_ip", ip == null ? null : ipToString(ip));
            out.put("host", host);
            out.put("path", path);
            out.put("query", query);
            out.put("machine", machine);
            out.put("headers", headers);
            out.put("body_len", bodyLen);

            return out;
        }

        private static String ipToString(byte[] ipBytes) {
            try {
                return InetAddress.getByAddress(ipBytes).getHostAddress();
            } catch (Exception e) {
                return null;
            }
        }

        // ----------------- shared helpers -----------------

        private static byte methodToByte(String m) {
            if (m == null) return 0;
            return switch (m) {
                case "GET" -> 1;
                case "POST" -> 2;
                case "PUT" -> 3;
                case "PATCH" -> 4;
                case "DELETE" -> 5;
                case "HEAD" -> 6;
                case "OPTIONS" -> 7;
                default -> 0;
            };
        }

        private static byte schemeToByte(String s) {
            if (s == null) return 0;
            return switch (s) {
                case "http" -> 1;
                case "https" -> 2;
                default -> 0;
            };
        }

        private static String byteToMethod(int code) {
            return switch (code) {
                case 1 -> "GET";
                case 2 -> "POST";
                case 3 -> "PUT";
                case 4 -> "PATCH";
                case 5 -> "DELETE";
                case 6 -> "HEAD";
                case 7 -> "OPTIONS";
                default -> "";
            };
        }

        private static String byteToScheme(int code) {
            return switch (code) {
                case 1 -> "http";
                case 2 -> "https";
                default -> "";
            };
        }

        private static void writeVarUtf8(ByteArrayOutputStream out, String s) {
            String x = (s == null) ? "" : s;
            byte[] b = x.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, b.length);
            out.writeBytes(b);
        }

        private static void writeVarAsciiLower(ByteArrayOutputStream out, String s) {
            if (s == null || s.isEmpty()) {
                writeVarint(out, 0);
                return;
            }

            int n = s.length();
            boolean ascii = true;
            for (int i = 0; i < n; i++) {
                if (s.charAt(i) > 0x7F) { ascii = false; break; }
            }
            if (!ascii) {
                writeVarUtf8(out, s.toLowerCase(Locale.ROOT));
                return;
            }

            writeVarint(out, n);
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
                out.write((byte) c);
            }
        }

        private static void writeU8(ByteArrayOutputStream out, int v) {
            out.write(v & 0xFF);
        }

        private static void writeInt16(ByteArrayOutputStream out, int v) {
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private static void writeInt32(ByteArrayOutputStream out, int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private static void writeVarint(ByteArrayOutputStream out, int v) {
            int x = v;
            while ((x & ~0x7F) != 0) {
                out.write((x & 0x7F) | 0x80);
                x >>>= 7;
            }
            out.write(x);
        }

        private static void writeVarint64(ByteArrayOutputStream out, long v) {
            long x = v;
            while ((x & ~0x7FL) != 0) {
                out.write((int) (x & 0x7F) | 0x80);
                x >>>= 7;
            }
            out.write((int) x);
        }

        private static int readU8(ByteBuffer b) throws EOFException {
            if (!b.hasRemaining()) throw new EOFException();
            return b.get() & 0xFF;
        }

        private static int readInt16(ByteBuffer b) throws EOFException {
            if (b.remaining() < 2) throw new EOFException();
            return ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
        }

        private static int readInt32(ByteBuffer b) throws EOFException {
            if (b.remaining() < 4) throw new EOFException();
            return ((b.get() & 0xFF) << 24)
                   | ((b.get() & 0xFF) << 16)
                   | ((b.get() & 0xFF) << 8)
                   | (b.get() & 0xFF);
        }

        private static int readVarint(ByteBuffer b) throws EOFException, IOException {
            int shift = 0;
            int result = 0;
            while (shift < 32) {
                int x = readU8(b);
                result |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("varint too long");
        }

        private static long readVarint64(ByteBuffer b) throws EOFException, IOException {
            int shift = 0;
            long result = 0;
            while (shift < 64) {
                int x = readU8(b);
                result |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("varint64 too long");
        }

        private static String readVarUtf8(ByteBuffer b) throws EOFException, IOException {
            int len = readVarint(b);
            if (len < 0) throw new IOException("negative utf8 len");
            if (len > MAX_UTF8_LEN) throw new IOException("utf8 token too large: " + len);
            if (b.remaining() < len) throw new EOFException();
            byte[] data = new byte[len];
            b.get(data);
            return new String(data, StandardCharsets.UTF_8);
        }

        private PayloadV2() {}
    }

    // ----------------------------- Common output -----------------------------

    public static final class DecodedMeta {
        public final Map<String, Object> meta;
        public final int metaBytes;

        public DecodedMeta(Map<String, Object> meta, int metaBytes) {
            this.meta = Objects.requireNonNull(meta, "meta");
            this.metaBytes = metaBytes;
        }
    }
}
