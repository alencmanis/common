package com.example.common.spool;


import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Payload {
    static final int MAGIC = 0x484B5031; // 'H''K''P''1'

    static Map<String, Object> decodeMeta(ByteBuffer meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        ByteBuffer b = meta.asReadOnlyBuffer();
        b.order(ByteOrder.BIG_ENDIAN);

        if (b.remaining() < 6) throw new EOFException("meta too small");

        int magic = readInt32(b);
        if (magic != MAGIC) {
            throw new IOException("bad meta magic: 0x" + Integer.toHexString(magic));
        }

        int flags = readInt16(b); // currently unused
        int methodCode = readU8(b);
        int schemeCode = readU8(b);

        String method = byteToMethod(methodCode);
        String scheme = byteToScheme(schemeCode);

        byte[] remoteIpBytes = null;
        int ipLen = readU8(b);
        if (ipLen > 0) {
            if (b.remaining() < ipLen) throw new EOFException("remoteIp truncated");
            remoteIpBytes = new byte[ipLen];
            b.get(remoteIpBytes);
        }

        String host  = readVarUtf8(b);
        String path  = readVarUtf8(b);
        String query = readVarUtf8(b);
        String machine = readVarUtf8(b);

        int headersBytesLen = readVarint(b);
        if (headersBytesLen < 0) throw new IOException("negative headersBytesLen");
        if (b.remaining() < headersBytesLen) throw new EOFException("headers section truncated");

        ByteBuffer hb = slice(b, headersBytesLen);
        Map<String, Object> headers = decodeHeadersBestEffort(hb);

        long bodyLen = readVarint64(b);
        if (bodyLen < 0) throw new IOException("negative bodyLen");

        // Build JSON-friendly map
        Map<String, Object> out = new LinkedHashMap<>(16);
        out.put("magic", "HKP1");
        out.put("flags", flags);
        out.put("method", method);
        out.put("scheme", scheme);
        out.put("host", host);
        out.put("path", path);
        out.put("query", query);
        out.put("machine", machine);
        out.put("remote_ip", (remoteIpBytes == null) ? null : ipToString(remoteIpBytes));
        out.put("headers", headers);
        out.put("body_len", bodyLen);

        return out;
    }

    // ----------------- helpers -----------------

    private static Map<String, Object> decodeHeadersBestEffort(ByteBuffer hb) throws IOException {
        // The encoder writes: name, then N values, then next name...
        // But N is not encoded. We use a heuristic to decide whether a token is a header name.
        Map<String, List<String>> tmp = new LinkedHashMap<>();

        String currentName = null;

        while (hb.hasRemaining()) {
            String token = readVarAsciiOrUtf8(hb); // name or value token

            if (currentName == null) {
                currentName = token;
                tmp.computeIfAbsent(currentName, k -> new ArrayList<>());
                continue;
            }

            // token is *at least* one value for currentName
            tmp.get(currentName).add(token);

            // Peek next token: if it looks like a header name, switch; otherwise it is another value
            if (hb.hasRemaining()) {
                hb.mark();
                String next = readVarAsciiOrUtf8(hb);
                boolean nextLooksLikeName = looksLikeHeaderName(next);

                hb.reset(); // rewind to let loop consume it normally

                if (nextLooksLikeName) {
                    // next iteration will read it as the new name
                    currentName = null;
                }
            }
        }

        // Convert to JSON-friendly (String or List<String>)
        Map<String, Object> headers = new LinkedHashMap<>();
        for (var e : tmp.entrySet()) {
            List<String> vals = e.getValue();
            if (vals.isEmpty()) {
                headers.put(e.getKey(), "");
            } else if (vals.size() == 1) {
                headers.put(e.getKey(), vals.get(0));
            } else {
                headers.put(e.getKey(), vals);
            }
        }
        return headers;
    }

    private static boolean looksLikeHeaderName(String s) {
        if (s == null || s.isEmpty()) return false;
        // Must be ASCII and mostly [a-z0-9-]
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                         || (c >= '0' && c <= '9')
                         || c == '-' || c == '_';
            if (!ok) return false;
        }
        // Typical headers contain '-' (content-type, user-agent). Not required, but helps.
        return n >= 2;
    }

    private static String ipToString(byte[] ipBytes) {
        try {
            return InetAddress.getByAddress(ipBytes).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static ByteBuffer slice(ByteBuffer b, int len) {
        ByteBuffer s = b.slice();
        s.limit(len);
        b.position(b.position() + len);
        return s;
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
        if (b.remaining() < len) throw new EOFException();
        byte[] data = new byte[len];
        b.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readVarAsciiOrUtf8(ByteBuffer b) throws EOFException, IOException {
        // In encoder: names are ASCII-lowered if ASCII, otherwise UTF-8.
        // Both are stored as varint length + raw bytes, so we can decode as UTF-8 safely.
        return readVarUtf8(b);
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

    private Payload() {}
}
