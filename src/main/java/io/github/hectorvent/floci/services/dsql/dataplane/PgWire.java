package io.github.hectorvent.floci.services.dsql.dataplane;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL's frontend/backend protocol, version 3: the messages the DSQL endpoint reads and writes. */
final class PgWire {

    static final int PROTOCOL_3 = 196608;
    static final int SSL_REQUEST = 80877103;
    static final int GSSENC_REQUEST = 80877104;
    static final int CANCEL_REQUEST = 80877102;
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;

    private PgWire() {
    }

    /** A message after the startup: its type byte and its body, without the length. */
    record Message(byte type, byte[] body) {
    }

    /** The first packet of a connection, which has no type byte: its code and the rest of its body. */
    record Startup(int code, byte[] body) {
    }

    static Startup readStartup(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = data.readInt();
        if (length < 8 || length > 10_000) {
            throw new IOException("Invalid startup packet length " + length);
        }
        int code = data.readInt();
        byte[] body = new byte[length - 8];
        data.readFully(body);
        return new Startup(code, body);
    }

    static Message read(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException();
        }
        DataInputStream data = new DataInputStream(in);
        int length = data.readInt();
        if (length < 4 || length > MAX_MESSAGE) {
            throw new IOException("Invalid message length " + length);
        }
        byte[] body = new byte[length - 4];
        data.readFully(body);
        return new Message((byte) type, body);
    }

    static void write(OutputStream out, byte type, byte[] body) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(5);
        header.put(type);
        header.putInt(body.length + 4);
        out.write(header.array());
        out.write(body);
    }

    static void write(OutputStream out, Message message) throws IOException {
        write(out, message.type(), message.body());
    }

    /** The {@code name=value} pairs of a StartupMessage body. */
    static Map<String, String> startupParameters(byte[] body) {
        Map<String, String> parameters = new LinkedHashMap<>();
        List<String> strings = cStrings(body, 0, body.length);
        for (int i = 0; i + 1 < strings.size(); i += 2) {
            if (strings.get(i).isEmpty()) {
                break;
            }
            parameters.put(strings.get(i), strings.get(i + 1));
        }
        return parameters;
    }

    static byte[] startupMessage(Map<String, String> parameters) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, PROTOCOL_3);
        parameters.forEach((name, value) -> {
            writeCString(body, name);
            writeCString(body, value);
        });
        body.write(0);
        byte[] content = body.toByteArray();
        ByteBuffer packet = ByteBuffer.allocate(content.length + 4);
        packet.putInt(content.length + 4);
        packet.put(content);
        return packet.array();
    }

    /** An ErrorResponse ({@code E}) or NoticeResponse ({@code N}) body from its fields, in order. */
    static byte[] fields(Map<Character, String> fields) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fields.forEach((code, value) -> {
            body.write(code);
            writeCString(body, value);
        });
        body.write(0);
        return body.toByteArray();
    }

    static Map<Character, String> parseFields(byte[] body) {
        Map<Character, String> fields = new LinkedHashMap<>();
        int i = 0;
        while (i < body.length && body[i] != 0) {
            char code = (char) body[i++];
            int end = i;
            while (end < body.length && body[end] != 0) {
                end++;
            }
            fields.put(code, new String(body, i, end - i, StandardCharsets.UTF_8));
            i = end + 1;
        }
        return fields;
    }

    /** An ErrorResponse of {@code severity} (FATAL, ERROR). */
    static byte[] error(String severity, String state, String message, String detail, String hint) {
        Map<Character, String> fields = new LinkedHashMap<>();
        fields.put('S', severity);
        fields.put('V', severity);
        fields.put('C', state);
        fields.put('M', message);
        if (detail != null) {
            fields.put('D', detail);
        }
        if (hint != null) {
            fields.put('H', hint);
        }
        return fields(fields);
    }

    static byte[] int32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    /** The NUL-terminated string at {@code offset}, and where the next field starts. */
    record CString(String value, int next) {
    }

    static CString cString(byte[] body, int offset) {
        int end = offset;
        while (end < body.length && body[end] != 0) {
            end++;
        }
        return new CString(new String(body, offset, end - offset, StandardCharsets.UTF_8), end + 1);
    }

    static byte[] cStringBytes(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeCString(out, value);
        return out.toByteArray();
    }

    private static List<String> cStrings(byte[] body, int from, int to) {
        List<String> strings = new ArrayList<>();
        int i = from;
        while (i < to) {
            CString string = cString(body, i);
            strings.add(string.value());
            i = string.next();
        }
        return strings;
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(int32(value));
    }
}
