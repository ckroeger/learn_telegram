package telegram.lernen.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeTelegramServerTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private FakeTelegramServer fake;

    @BeforeEach
    void start() throws IOException {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private HttpResponse<String> post(String methode, String json) throws Exception {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/botTOKEN/" + methode))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(anfrage, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getMeLiefertEinenBot() throws Exception {
        HttpResponse<String> antwort = post("getMe", "{}");
        assertEquals(200, antwort.statusCode());
        assertTrue(antwort.body().contains("\"ok\":true"));
        assertTrue(antwort.body().contains("\"username\":\"fake_bot\""));
    }

    @Test
    void getUpdatesLiefertUpdatesBisSieBestaetigtSind() throws Exception {
        fake.enqueueTextMessage(42, "Hallo");

        String ersteAntwort = post("getUpdates", "{\"offset\":0,\"timeout\":0}").body();
        assertTrue(ersteAntwort.contains("\"update_id\":1"));
        assertTrue(ersteAntwort.contains("\"text\":\"Hallo\""));

        // Ohne Bestätigung kommt das Update erneut, wie bei Telegram.
        assertTrue(post("getUpdates", "{\"offset\":0,\"timeout\":0}").body().contains("\"update_id\":1"));

        // offset > update_id bestätigt es.
        assertEquals("{\"ok\":true,\"result\":[]}", post("getUpdates", "{\"offset\":2,\"timeout\":0}").body());
    }

    @Test
    void getUpdatesWartetAufNeueUpdates() throws Exception {
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            fake.enqueueTextMessage(7, "spaet");
        }).start();

        String antwort = post("getUpdates", "{\"timeout\":30}").body();
        assertTrue(antwort.contains("\"text\":\"spaet\""));
    }

    @Test
    void getUpdatesLiestOffsetAuchAusDerQuery() throws Exception {
        fake.enqueueTextMessage(1, "a");
        HttpRequest anfrage = HttpRequest.newBuilder(
                URI.create(fake.baseUrl() + "/botTOKEN/getUpdates?offset=2&timeout=0")).build();
        String antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("{\"ok\":true,\"result\":[]}", antwort);
    }

    @Test
    void sendMessageGibtChatUndTextZurueckUndWirdAufgezeichnet() throws Exception {
        String antwort = post("sendMessage", "{\"chat_id\":42,\"text\":\"Zeile 1\\nZeile \\\"2\\\"\"}").body();
        assertTrue(antwort.contains("\"chat\":{\"id\":42,"));
        assertTrue(antwort.contains("\"text\":\"Zeile 1\\nZeile \\\"2\\\"\""));

        RecordedRequest aufruf = fake.requests("sendMessage").get(0);
        assertEquals("TOKEN", aufruf.token());
        assertTrue(aufruf.bodyContains("\"chat_id\":42"));
    }

    @Test
    void sendMessageVersteht_Formulardaten() throws Exception {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/botTOKEN/sendMessage"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("chat_id=5&text=Hallo+Welt%21"))
                .build();
        String antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(antwort.contains("\"chat\":{\"id\":5,"));
        assertTrue(antwort.contains("\"text\":\"Hallo Welt!\""));
    }

    @Test
    void unbekannteMethodeLiefertOkFalseMit404() throws Exception {
        HttpResponse<String> antwort = post("gibtEsNicht", "{}");
        assertEquals(404, antwort.statusCode());
        assertTrue(antwort.body().contains("\"ok\":false"));
    }

    @Test
    void onMethodLiefertFesteAntwort() throws Exception {
        fake.onMethod("getChatMemberCount", "42");
        assertEquals("{\"ok\":true,\"result\":42}", post("getChatMemberCount", "{}").body());
    }

    @Test
    void rateLimitGiltNurFuerDenNaechstenAufruf() throws Exception {
        fake.rateLimitNext("sendMessage", 3);

        HttpResponse<String> begrenzt = post("sendMessage", "{\"chat_id\":1,\"text\":\"x\"}");
        assertEquals(429, begrenzt.statusCode());
        assertTrue(begrenzt.body().contains("\"retry_after\":3"));

        assertEquals(200, post("sendMessage", "{\"chat_id\":1,\"text\":\"x\"}").statusCode());
    }

    @Test
    void failNextLiefertFehlerAntwort() throws Exception {
        fake.failNext("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        HttpResponse<String> antwort = post("getMe", "{}");
        assertEquals(401, antwort.statusCode());
        assertTrue(antwort.body().contains("Unauthorized"));
        assertFalse(post("getMe", "{}").body().contains("Unauthorized"));
    }

    @Test
    void delayNextVerzoegertDenAufruf() throws Exception {
        fake.delayNext("getMe", Duration.ofMillis(300));
        long start = System.nanoTime();
        post("getMe", "{}");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 300);
    }
}
