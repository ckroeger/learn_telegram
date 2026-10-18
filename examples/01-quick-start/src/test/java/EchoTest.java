import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class EchoTest {

    private static final String TOKEN = "123456:GEHEIM";

    /** Startet den Echo-Bot in einem Thread, bis die Prüfung fertig ist; beendet ihn dann per Interrupt. */
    private static void mitLaufendemBot(FakeTelegramServer fake, ThrowingRunnable pruefung) throws Exception {
        AtomicReference<Throwable> botFehler = new AtomicReference<>();
        Thread bot = new Thread(() -> {
            try {
                new Echo(TOKEN, fake.baseUrl()).run();
            } catch (InterruptedException e) {
                // erwartetes Ende: der Test unterbricht den Bot
            } catch (Throwable t) {
                botFehler.set(t);
            }
        });
        bot.start();
        try {
            pruefung.run();
        } finally {
            bot.interrupt();
            bot.join(5000);
        }
        assertEquals(null, botFehler.get());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void antwortetMitDemGleichenText() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.enqueueTextMessage(42, "Hallo \"Welt\"");

            mitLaufendemBot(fake, () -> {
                RecordedRequest gesendet = fake.awaitRequests("sendMessage", 1, Duration.ofSeconds(5)).get(0);
                assertTrue(gesendet.bodyContains("\"chat_id\":42"));
                assertTrue(gesendet.bodyContains("\"text\":\"Hallo \\\"Welt\\\"\""));
            });
        }
    }

    @Test
    void bestaetigtUpdatesPerOffset() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.enqueueTextMessage(1, "eins");
            fake.enqueueTextMessage(1, "zwei");

            mitLaufendemBot(fake, () -> {
                fake.awaitRequests("sendMessage", 2, Duration.ofSeconds(5));
                // Nach beiden Updates (update_id 1 und 2) fragt der Bot mit offset 3 weiter.
                Thread.sleep(200);
                RecordedRequest letzte = fake.requests("getUpdates").getLast();
                assertTrue(letzte.bodyContains("\"offset\":3"), letzte.body());
                assertEquals(2, fake.requests("sendMessage").size());
            });
        }
    }

    @Test
    void ignoriertUpdatesOhneText() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.enqueueUpdate("message", "{\"message_id\":1,\"chat\":{\"id\":9,\"type\":\"private\"},\"date\":1,"
                    + "\"sticker\":{\"file_id\":\"x\"}}");
            fake.enqueueTextMessage(9, "danach");

            mitLaufendemBot(fake, () -> {
                RecordedRequest gesendet = fake.awaitRequests("sendMessage", 1, Duration.ofSeconds(5)).get(0);
                assertTrue(gesendet.bodyContains("\"text\":\"danach\""));
                assertEquals(1, fake.requests("sendMessage").size());
            });
        }
    }

    @Test
    void meldetTelegramFehlerOhneDenTokenZuNennen() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.failNext("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

            IllegalStateException fehler = assertThrows(IllegalStateException.class,
                    () -> new Echo(TOKEN, fake.baseUrl()).run());
            assertTrue(fehler.getMessage().contains("Unauthorized"));
            assertFalse(fehler.getMessage().contains("GEHEIM"));
        }
    }
}
