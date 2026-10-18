package telegram.lernen.longpolling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class UpdatePollerTest {

    private static final String TOKEN = "123456:GEHEIM";

    private interface Pruefung {
        void run() throws Exception;
    }

    /** Lässt den Poller in einem Thread laufen, bis die Prüfung fertig ist, und unterbricht ihn dann. */
    private static void mitLaufendemPoller(UpdatePoller poller, java.util.function.Consumer<JsonNode> handler,
            Pruefung pruefung) throws Exception {
        AtomicReference<Throwable> fehler = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                poller.laufen(handler);
            } catch (InterruptedException e) {
                // erwartetes Ende
            } catch (Throwable t) {
                fehler.set(t);
            }
        });
        thread.start();
        try {
            pruefung.run();
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
        assertEquals(null, fehler.get());
    }

    @Test
    void liefertUpdatesInReihenfolgeUndBestaetigtSie() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.enqueueTextMessage(1, "eins");
            fake.enqueueTextMessage(1, "zwei");
            fake.enqueueTextMessage(1, "drei");
            List<String> texte = Collections.synchronizedList(new ArrayList<>());

            mitLaufendemPoller(new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of()),
                    update -> texte.add(update.path("message").path("text").asText()), () -> {
                        long ende = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (texte.size() < 3 && System.nanoTime() < ende) {
                            Thread.sleep(20);
                        }
                        assertEquals(List.of("eins", "zwei", "drei"), texte);
                        // Nach update_id 3 fragt der Poller mit offset 4 weiter.
                        Thread.sleep(200);
                        RecordedRequest letzte = fake.requests("getUpdates").getLast();
                        assertTrue(letzte.bodyContains("\"offset\":4"), letzte.body());
                    });
        }
    }

    @Test
    void sendetAllowedUpdatesUndTimeout() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            mitLaufendemPoller(new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of("message", "callback_query")),
                    update -> { }, () -> {
                        RecordedRequest erste = fake.awaitRequests("getUpdates", 1, Duration.ofSeconds(5)).get(0);
                        assertTrue(erste.bodyContains("\"allowed_updates\":[\"message\",\"callback_query\"]"), erste.body());
                        assertTrue(erste.bodyContains("\"timeout\":30"));
                        assertTrue(erste.bodyContains("\"offset\":0"));
                    });
        }
    }

    @Test
    void eineLeereListeSendetTrotzdemAllowedUpdatesUmDieStandardEinstellungZuSetzen() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            mitLaufendemPoller(new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of()), update -> { }, () -> {
                RecordedRequest erste = fake.awaitRequests("getUpdates", 1, Duration.ofSeconds(5)).get(0);
                assertTrue(erste.bodyContains("\"allowed_updates\":[]"), erste.body());
            });
        }
    }

    @Test
    void wartetBeiRateLimitDieGeforderteZeitUndMachtWeiter() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.rateLimitNext("getUpdates", 7);
            fake.enqueueTextMessage(1, "danach");
            List<Long> pausen = Collections.synchronizedList(new ArrayList<>());
            List<String> texte = Collections.synchronizedList(new ArrayList<>());

            mitLaufendemPoller(new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of(), pausen::add),
                    update -> texte.add(update.path("message").path("text").asText()), () -> {
                        long ende = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (texte.isEmpty() && System.nanoTime() < ende) {
                            Thread.sleep(20);
                        }
                        assertEquals(List.of(7L), pausen);
                        assertEquals(List.of("danach"), texte);
                    });
        }
    }

    @Test
    void netzwerkfehlerFuehrenZuEinerPauseStattZumAbbruch() throws Exception {
        String toteUrl;
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            toteUrl = fake.baseUrl();
        }
        List<Long> pausen = new ArrayList<>();
        UpdatePoller poller = new UpdatePoller(new BotApi(TOKEN, toteUrl), List.of(), sekunden -> {
            pausen.add(sekunden);
            throw new InterruptedException("Test beendet die Schleife");
        });

        assertThrows(InterruptedException.class, () -> poller.laufen(update -> { }));
        assertEquals(List.of(5L), pausen);
    }

    @Test
    void einFehlerImHandlerStopptDieSchleifeNicht() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.enqueueTextMessage(1, "kaputt");
            fake.enqueueTextMessage(1, "ganz");
            List<String> ganze = Collections.synchronizedList(new ArrayList<>());

            mitLaufendemPoller(new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of()), update -> {
                String text = update.path("message").path("text").asText();
                if (text.equals("kaputt")) {
                    throw new IllegalStateException("absichtlich");
                }
                ganze.add(text);
            }, () -> {
                long ende = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (ganze.isEmpty() && System.nanoTime() < ende) {
                    Thread.sleep(20);
                }
                assertEquals(List.of("ganz"), ganze);
            });
        }
    }

    @Test
    void andereTelegramFehlerBeendenDieSchleife() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.failNext("getUpdates", 409, "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: webhook is active\"}");
            UpdatePoller poller = new UpdatePoller(new BotApi(TOKEN, fake.baseUrl()), List.of());

            TelegramException fehler = assertThrows(TelegramException.class, () -> poller.laufen(update -> { }));
            assertEquals(409, fehler.errorCode());
        }
    }
}
