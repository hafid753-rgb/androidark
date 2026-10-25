// Drives the mock HA server the way the Android client does, so the WebSocket
// protocol can be checked without the emulator.
//
//   java tools/mock-ha/WsProbe.java [ws://host:port/api/websocket]

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WsProbe {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://127.0.0.1:8123/api/websocket";
        CountDownLatch done = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();

        WebSocket.Listener listener = new WebSocket.Listener() {
            final StringBuilder buffer = new StringBuilder();
            int step = 0;

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                ws.request(1);
                if (!last) return null;
                String message = buffer.toString();
                buffer.setLength(0);

                System.out.println("<- " + (message.length() > 400 ? message.substring(0, 400) + "..." : message));

                // Strictly step-driven: each reply advances exactly one step, so
                // an unrelated push cannot re-trigger an earlier request.
                if (step == 0 && message.contains("auth_required")) {
                    step = 1;
                    say(ws, "{\"type\":\"auth\",\"access_token\":\"mock-token\"}");
                } else if (step == 1 && message.contains("auth_ok")) {
                    step = 2;
                    say(ws, "{\"id\":1,\"type\":\"subscribe_entities\"}");
                } else if (step == 2 && message.contains("\"a\":{")) {
                    step = 3;
                    say(ws, "{\"id\":2,\"type\":\"config/area_registry/list\"}");
                } else if (step == 3 && message.contains("\"area_id\"")) {
                    step = 4;
                    say(ws, "{\"id\":3,\"type\":\"config/entity_registry/list\"}");
                } else if (step == 4 && message.contains("\"entity_id\"")) {
                    step = 5;
                    say(ws, "{\"id\":4,\"type\":\"call_service\",\"domain\":\"homeassistant\","
                            + "\"service\":\"toggle\",\"target\":{\"entity_id\":\"light.kitchen_spots\"}}");
                } else if (step == 5 && message.contains("\"c\":{")) {
                    step = 6;
                    say(ws, "{\"id\":5,\"type\":\"call_service\",\"domain\":\"climate\","
                            + "\"service\":\"set_temperature\",\"target\":{\"entity_id\":"
                            + "\"climate.living_room_ac\"},\"service_data\":{\"temperature\":25.5}}");
                } else if (step == 6 && message.contains("\"temperature\":25.5")) {
                    System.out.println("OK: toggle and set_temperature both echoed back as diffs");
                    done.countDown();
                }
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.out.println("error: " + error);
                done.countDown();
            }

            private void say(WebSocket ws, String text) {
                System.out.println("-> " + text);
                ws.sendText(text, true);
            }
        };

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .get(10, TimeUnit.SECONDS);

        if (!done.await(20, TimeUnit.SECONDS)) {
            System.out.println("TIMEOUT: exchange did not complete");
            System.exit(1);
        }
        ws.abort();
    }
}
