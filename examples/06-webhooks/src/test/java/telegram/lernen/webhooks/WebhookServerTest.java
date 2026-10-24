package telegram.lernen.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookServerTest {

    private static final String GEHEIMNIS = "geheim-123_ABC";
    private static final String UPDATE = "{\"update_id\":%d,\"message\":{\"message_id\":1,\"chat\":{\"id\":42},"
            + "\"text\":\"Hallo\"}}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final BlockingQueue<JsonNode> empfangen = new LinkedBlockingQueue<>();
    private WebhookServer server;

    @BeforeEach
    void start() throws Exception {
        server = WebhookServer.starten(0, "/hook", GEHEIMNIS, empfangen::add);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> senden(String methode, String secret, String koerper) throws Exception {
        HttpRequest.Builder anfrage = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/hook"))
                .method(methode, methode.equals("POST") ? HttpRequest.BodyPublishers.ofString(koerper)
                        : HttpRequest.BodyPublishers.noBody());
        if (secret != null) {
            anfrage.header(WebhookServer.SECRET_HEADER, secret);
        }
        return http.send(anfrage.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void einUpdateMitRichtigemGeheimnisWirdAngenommenUndWeitergegeben() throws Exception {
        HttpResponse<String> antwort = senden("POST", GEHEIMNIS, UPDATE.formatted(7));

        assertEquals(200, antwort.statusCode());
        JsonNode update = empfangen.poll(5, TimeUnit.SECONDS);
        assertEquals(7, update.get("update_id").asLong());
        assertEquals("Hallo", update.path("message").path("text").asText());
    }

    @Test
    void falschesOderFehlendesGeheimnisWirdMit403AbgelehntUndNichtVerarbeitet() throws Exception {
        assertEquals(403, senden("POST", "falsch", UPDATE.formatted(1)).statusCode());
        assertEquals(403, senden("POST", null, UPDATE.formatted(2)).statusCode());
        assertEquals(403, senden("POST", GEHEIMNIS + "x", UPDATE.formatted(3)).statusCode());
        assertEquals(403, senden("POST", "", UPDATE.formatted(4)).statusCode());

        assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(5)).statusCode());
        assertEquals(5, empfangen.poll(5, TimeUnit.SECONDS).get("update_id").asLong());
        assertTrue(empfangen.isEmpty(), "die abgelehnten Updates kamen nicht beim Handler an");
    }

    @Test
    void nurPostIstErlaubt() throws Exception {
        HttpResponse<String> antwort = senden("GET", GEHEIMNIS, null);

        assertEquals(405, antwort.statusCode());
        assertEquals("POST", antwort.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void ungueltigesJsonOderKeinUpdateWirdMit400AbgelehntAuchMitRichtigemGeheimnis() throws Exception {
        assertEquals(400, senden("POST", GEHEIMNIS, "kein json").statusCode());
        assertEquals(400, senden("POST", GEHEIMNIS, "{\"foo\":1}").statusCode());
        assertEquals(400, senden("POST", GEHEIMNIS, "").statusCode());
        assertTrue(empfangen.isEmpty());
    }

    @Test
    void zuGrosseAnfragenWerdenMit413AbgelehntUndGrenzfallKommtDurch() throws Exception {
        String gross = "{\"update_id\":1,\"x\":\"" + "a".repeat(WebhookServer.MAX_BYTES) + "\"}";
        assertEquals(413, senden("POST", GEHEIMNIS, gross).statusCode());

        String vollAusgeschoepft = "{\"update_id\":2,\"x\":\"";
        String passt = vollAusgeschoepft + "a".repeat(WebhookServer.MAX_BYTES - vollAusgeschoepft.length() - 2) + "\"}";
        assertEquals(WebhookServer.MAX_BYTES, passt.length());
        assertEquals(200, senden("POST", GEHEIMNIS, passt).statusCode());
    }

    @Test
    void einFehlerImHandlerAendertDieAntwortAnTelegramNichtUndStopptDenServerNicht() throws Exception {
        server.close();
        List<Long> ids = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch zweiter = new CountDownLatch(1);
        server = WebhookServer.starten(0, "/hook", GEHEIMNIS, update -> {
            long id = update.get("update_id").asLong();
            ids.add(id);
            if (id == 1) {
                throw new IllegalStateException("absichtlich");
            }
            zweiter.countDown();
        });

        assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(1)).statusCode());
        assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(2)).statusCode());

        assertTrue(zweiter.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L), ids);
    }

    @Test
    void updatesWerdenInDerReihenfolgeDesEingangsVerarbeitet() throws Exception {
        for (int i = 1; i <= 30; i++) {
            assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(i)).statusCode());
        }

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(empfangen.poll(5, TimeUnit.SECONDS).get("update_id").asLong());
        }
        for (int i = 0; i < 30; i++) {
            assertEquals(i + 1, ids.get(i));
        }
    }

    @Test
    void istDieWarteschlangeVollAntwortetDerServerMit503() throws Exception {
        server.close();
        CountDownLatch blockiert = new CountDownLatch(1);
        CountDownLatch gestartet = new CountDownLatch(1);
        server = WebhookServer.starten(0, "/hook", GEHEIMNIS, update -> {
            gestartet.countDown();
            try {
                blockiert.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(0)).statusCode());
        assertTrue(gestartet.await(5, TimeUnit.SECONDS), "der Handler hängt jetzt fest");
        for (int i = 1; i <= WebhookServer.WARTESCHLANGE; i++) {
            assertEquals(200, senden("POST", GEHEIMNIS, UPDATE.formatted(i)).statusCode(), "Update " + i);
        }
        assertEquals(503, senden("POST", GEHEIMNIS, UPDATE.formatted(9999)).statusCode(),
                "Telegram wiederholt Anfragen mit 503 später");
        blockiert.countDown();
    }

    @Test
    void einAndererPfadIstNichtVorhanden() throws Exception {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/anders"))
                .header(WebhookServer.SECRET_HEADER, GEHEIMNIS)
                .POST(HttpRequest.BodyPublishers.ofString(UPDATE.formatted(1))).build();
        assertEquals(404, http.send(anfrage, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void ohneGeheimnisStartetDerServerNicht() {
        assertThrows(IllegalArgumentException.class, () -> WebhookServer.starten(0, "/hook", "", u -> { }));
        assertThrows(IllegalArgumentException.class, () -> WebhookServer.starten(0, "/hook", null, u -> { }));
    }

    @Test
    void gleichVergleichtBytesGenauUndKommtMitNullZurecht() {
        byte[] erwartet = "geheim".getBytes();
        assertTrue(WebhookServer.gleich(erwartet, "geheim"));
        assertFalse(WebhookServer.gleich(erwartet, "geheiM"));
        assertFalse(WebhookServer.gleich(erwartet, "geheim "));
        assertFalse(WebhookServer.gleich(erwartet, "geh"));
        assertFalse(WebhookServer.gleich(erwartet, null));
        assertFalse(WebhookServer.gleich(erwartet, ""));
    }
}
