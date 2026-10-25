// A throwaway Home Assistant stand-in for exercising the Android client
// without a real box. Speaks just enough of the WebSocket API that the app
// authenticates, lists rooms, toggles entities and loads camera frames.
//
//   java tools/mock-ha/MockHa.java [port]
//
// From an emulator the host is 10.0.2.2, so the app connects to
// http://10.0.2.2:8123 with any non-empty token.
//
// Hand-rolled RFC 6455 framing and string-built JSON on purpose: it has to run
// on a bare JDK with no dependencies.

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

public class MockHa {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** entity_id -> state. Mutated by call_service so toggles are visible. */
    private static final Map<String, String> STATES = new ConcurrentHashMap<>();
    /** entity_id -> attribute JSON body (no braces). */
    private static final Map<String, String> ATTRS = new ConcurrentHashMap<>();
    /** entity_id -> area name, for the registry replies. */
    private static final Map<String, String> AREAS = new LinkedHashMap<>();

    static {
        put("light.living_room_lamp", "on", "\"friendly_name\":\"Living Room Lamp\"", "Living Room");
        put("light.kitchen_spots", "off", "\"friendly_name\":\"Kitchen Spots\"", "Kitchen");
        put("switch.office_plug", "on", "\"friendly_name\":\"Office Plug\"", "Office");
        put("fan.bedroom_fan", "off", "\"friendly_name\":\"Bedroom Fan\"", "Bedroom");
        put("lock.front_door", "locked", "\"friendly_name\":\"Front Door Lock\"", "Hallway");
        put("media_player.living_room_tv", "off", "\"friendly_name\":\"Living Room TV\"", "Living Room");
        put("binary_sensor.front_door", "off",
                "\"friendly_name\":\"Front Door\",\"device_class\":\"door\"", "Hallway");
        put("sensor.living_room_temperature", "21.5",
                "\"friendly_name\":\"Living Room Temperature\",\"unit_of_measurement\":\"\\u00b0C\","
                        + "\"device_class\":\"temperature\"", "Living Room");
        put("sensor.bedroom_humidity", "48",
                "\"friendly_name\":\"Bedroom Humidity\",\"unit_of_measurement\":\"%\","
                        + "\"device_class\":\"humidity\"", "Bedroom");
        put("camera.front_door", "idle", "\"friendly_name\":\"Front Door Camera\"", "Hallway");
        put("camera.garden", "idle", "\"friendly_name\":\"Garden Camera\"", "Garden");
        put("climate.living_room_ac", "cool",
                "\"friendly_name\":\"Living Room AC\",\"current_temperature\":24.0,\"temperature\":22.0,"
                        + "\"min_temp\":16.0,\"max_temp\":30.0,\"target_temp_step\":0.5,"
                        + "\"hvac_modes\":[\"off\",\"cool\",\"heat\",\"fan_only\",\"dry\"],"
                        + "\"fan_mode\":\"medium\",\"fan_modes\":[\"low\",\"medium\",\"high\",\"auto\"]",
                "Living Room");
        put("climate.bedroom_ac", "off",
                "\"friendly_name\":\"Bedroom AC\",\"current_temperature\":22.5,\"temperature\":21.0,"
                        + "\"min_temp\":16.0,\"max_temp\":30.0,\"target_temp_step\":1.0,"
                        + "\"hvac_modes\":[\"off\",\"cool\",\"heat\"],"
                        + "\"fan_mode\":\"auto\",\"fan_modes\":[\"low\",\"high\",\"auto\"]",
                "Bedroom");
    }

    private static void put(String id, String state, String attrs, String area) {
        STATES.put(id, state);
        ATTRS.put(id, attrs);
        AREAS.put(id, area);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8123;
        ServerSocket server = new ServerSocket(port);
        System.out.println("MockHa listening on http://0.0.0.0:" + port + " (emulator: 10.0.2.2:" + port + ")");
        System.out.flush();
        while (true) {
            Socket socket = server.accept();
            Thread t = new Thread(() -> handle(socket));
            t.setDaemon(true);
            t.start();
        }
    }

    private static void handle(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
            String upgrade = headers.getOrDefault("upgrade", "");

            if ("websocket".equalsIgnoreCase(upgrade)) {
                System.out.println("ws connect " + path);
                doHandshake(out, headers.get("sec-websocket-key"));
                serveWebSocket(in, out);
            } else if (path.startsWith("/api/camera_proxy/")) {
                String entity = path.substring("/api/camera_proxy/".length());
                int q = entity.indexOf('?');
                if (q >= 0) entity = entity.substring(0, q);
                System.out.println("snapshot " + entity);
                byte[] png = renderFrame(entity);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                        + png.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(png);
                out.flush();
            } else {
                byte[] body = "{\"message\":\"mock\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            }
        } catch (Exception e) {
            // A disconnecting client is normal here; log and move on.
            System.out.println("conn ended: " + e);
        }
    }

    // MARK: - WebSocket

    private static void doHandshake(OutputStream out, String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.getEncoder().encodeToString(
                sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void serveWebSocket(InputStream in, OutputStream out) throws IOException {
        send(out, "{\"type\":\"auth_required\",\"ha_version\":\"2024.10.0\"}");
        while (true) {
            String message = readFrame(in, out);
            if (message == null) return;
            String type = str(message, "type");
            int id = intOf(message, "id");

            switch (type == null ? "" : type) {
                case "auth" -> {
                    System.out.println("auth -> ok");
                    send(out, "{\"type\":\"auth_ok\",\"ha_version\":\"2024.10.0\"}");
                }
                case "ping" -> send(out, "{\"id\":" + id + ",\"type\":\"pong\"}");
                case "subscribe_entities" -> {
                    result(out, id, null);
                    send(out, "{\"id\":" + id + ",\"type\":\"event\",\"event\":{\"a\":{" + compactAll() + "}}}");
                }
                case "subscribe_events" -> result(out, id, null);
                case "get_states" -> result(out, id, statesArray());
                case "config/area_registry/list" -> result(out, id, areaRegistry());
                case "config/device_registry/list" -> result(out, id, "[]");
                case "config/entity_registry/list" -> result(out, id, entityRegistry());
                case "call_service" -> {
                    applyService(message, out);
                    result(out, id, "{\"context\":{\"id\":\"mock\"}}");
                }
                default -> result(out, id, null);
            }
        }
    }

    private static void result(OutputStream out, int id, String resultJson) throws IOException {
        send(out, "{\"id\":" + id + ",\"type\":\"result\",\"success\":true,\"result\":"
                + (resultJson == null ? "null" : resultJson) + "}");
    }

    /** Applies the service call to local state and pushes the diff back. */
    private static void applyService(String message, OutputStream out) throws IOException {
        String domain = str(message, "domain");
        String service = str(message, "service");
        String entity = str(message, "entity_id");
        if (entity == null || !STATES.containsKey(entity)) return;
        System.out.println("call_service " + domain + "." + service + " on " + entity);

        if ("toggle".equals(service)) {
            String now = STATES.get(entity);
            STATES.put(entity, "on".equals(now) ? "off" : "on");
            pushState(out, entity);
            return;
        }
        switch (service) {
            case "set_hvac_mode" -> {
                String mode = str(message, "hvac_mode");
                if (mode != null) {
                    STATES.put(entity, mode);
                    pushState(out, entity);
                }
            }
            case "set_temperature" -> {
                double temperature = doubleOf(message, "temperature");
                if (!Double.isNaN(temperature)) {
                    ATTRS.put(entity, replaceNumber(ATTRS.get(entity), "temperature", temperature));
                    pushAttrs(out, entity);
                }
            }
            case "set_fan_mode" -> {
                String mode = str(message, "fan_mode");
                if (mode != null) {
                    ATTRS.put(entity, replaceString(ATTRS.get(entity), "fan_mode", mode));
                    pushAttrs(out, entity);
                }
            }
            default -> { }
        }
    }

    private static void pushState(OutputStream out, String entity) throws IOException {
        send(out, "{\"id\":1,\"type\":\"event\",\"event\":{\"c\":{\"" + entity + "\":{\"+\":{\"s\":\""
                + STATES.get(entity) + "\",\"lc\":" + epoch() + "}}}}}");
    }

    private static void pushAttrs(OutputStream out, String entity) throws IOException {
        send(out, "{\"id\":1,\"type\":\"event\",\"event\":{\"c\":{\"" + entity + "\":{\"+\":{\"a\":{"
                + ATTRS.get(entity) + "},\"lu\":" + epoch() + "}}}}}");
    }

    // MARK: - Payload builders

    private static String compactAll() {
        StringBuilder sb = new StringBuilder();
        for (String id : AREAS.keySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append('"').append(id).append("\":{\"s\":\"").append(STATES.get(id))
                    .append("\",\"a\":{").append(ATTRS.get(id)).append("},\"lc\":").append(epoch()).append('}');
        }
        return sb.toString();
    }

    private static String statesArray() {
        StringBuilder sb = new StringBuilder("[");
        for (String id : AREAS.keySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"entity_id\":\"").append(id).append("\",\"state\":\"").append(STATES.get(id))
                    .append("\",\"attributes\":{").append(ATTRS.get(id)).append("}}");
        }
        return sb.append(']').toString();
    }

    private static String areaRegistry() {
        StringBuilder sb = new StringBuilder("[");
        AREAS.values().stream().distinct().forEach(name -> {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"area_id\":\"").append(slug(name)).append("\",\"name\":\"").append(name).append("\"}");
        });
        return sb.append(']').toString();
    }

    private static String entityRegistry() {
        StringBuilder sb = new StringBuilder("[");
        AREAS.forEach((id, area) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"entity_id\":\"").append(id).append("\",\"area_id\":\"").append(slug(area))
                    .append("\",\"device_id\":null}");
        });
        return sb.append(']').toString();
    }

    private static String slug(String name) {
        return name.toLowerCase().replace(' ', '_');
    }

    private static long epoch() {
        return System.currentTimeMillis() / 1000;
    }

    // MARK: - Camera frames

    /** A gradient with the entity name and a clock, so refreshes are visible. */
    private static byte[] renderFrame(String entity) throws IOException {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        int seed = Math.abs(entity.hashCode());
        Color a = new Color((seed % 80) + 20, ((seed / 80) % 60) + 30, 90);
        Color b = new Color(10, 20, 40);
        g.setPaint(new GradientPaint(0, 0, a, 640, 360, b));
        g.fillRect(0, 0, 640, 360);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(entity, 28, 180);
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), 28, 220);
        g.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    // MARK: - Framing

    private static void send(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(0x81);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write((payload.length >> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (((long) payload.length >> shift) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    /** Returns the next text message, or null once the peer goes away. */
    private static String readFrame(InputStream in, OutputStream out) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        int opcode = first & 0x0F;
        int second = in.read();
        if (second < 0) return null;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
        }
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }

        if (opcode == 0x8) return null;             // close
        if (opcode == 0x9) {                        // ping -> pong
            synchronized (out) {
                out.write(0x8A);
                out.write(payload.length);
                out.write(payload);
                out.flush();
            }
            return readFrame(in, out);
        }
        if (opcode != 0x1) return readFrame(in, out);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("eof");
        return value;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("eof");
            offset += read;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buffer.write(c);
        }
        if (c < 0 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    // MARK: - Crude JSON reads (enough for the handful of fields used here)

    private static String str(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static int intOf(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return 0;
        int start = at + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        return end == start ? 0 : Integer.parseInt(json.substring(start, end));
    }

    private static double doubleOf(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return Double.NaN;
        int start = at + needle.length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static String replaceNumber(String attrs, String key, double value) {
        String needle = "\"" + key + "\":";
        int at = attrs.indexOf(needle);
        if (at < 0) return attrs + ",\"" + key + "\":" + value;
        int start = at + needle.length();
        int end = start;
        while (end < attrs.length() && attrs.charAt(end) != ',' && attrs.charAt(end) != '}') end++;
        return attrs.substring(0, start) + value + attrs.substring(end);
    }

    private static String replaceString(String attrs, String key, String value) {
        String needle = "\"" + key + "\":\"";
        int at = attrs.indexOf(needle);
        if (at < 0) return attrs + ",\"" + key + "\":\"" + value + "\"";
        int start = at + needle.length();
        int end = attrs.indexOf('"', start);
        return attrs.substring(0, start) + value + attrs.substring(end);
    }
}
