package telegram.lernen.apps.erinnerung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.testsupport.FakeTelegramServer;

/** Der Bot mit SQLite über einen simulierten Neustart: Programm schließen, Datei behalten, neu starten. */
class ErinnerungsBotNeustartTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-10-24T12:00:00Z");
    private static final long CHAT = 42;

    /** Eine Uhr, die die Tests vorstellen. */
    private static final class VerstellbareUhr extends Clock {
        volatile Instant jetzt = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }

    /** Merkt sich geplante Aufgaben, führt sie aber nur auf Befehl aus. */
    private static final class TestPlaner implements Planer {
        final List<Duration> verzoegerungen = new ArrayList<>();
        final List<Runnable> aufgaben = new ArrayList<>();
        final List<boolean[]> abgesagt = new ArrayList<>();

        @Override
        public Runnable planen(Duration verzoegerung, Runnable aufgabe) {
            boolean[] flag = new boolean[1];
            verzoegerungen.add(verzoegerung);
            aufgaben.add(aufgabe);
            abgesagt.add(flag);
            return () -> flag[0] = true;
        }
    }

    @TempDir
    Path ordner;

    private FakeTelegramServer fake;
    private final VerstellbareUhr uhr = new VerstellbareUhr();
    private final List<SqliteSpeicher> offeneSpeicher = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        for (String methode : List.of("answerCallbackQuery", "editMessageText", "deleteMessage")) {
            fake.onMethod(methode, "true");
        }
    }

    @AfterEach
    void stop() {
        offeneSpeicher.forEach(SqliteSpeicher::close);
        fake.close();
    }

    /** Startet "das Programm": öffnet dieselbe Datei und baut einen Bot darauf. */
    private ErinnerungsBot starten(SqliteSpeicher speicher, Planer planer) {
        return new ErinnerungsBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", speicher, planer,
                uhr);
    }

    private SqliteSpeicher datei() {
        SqliteSpeicher speicher = SqliteSpeicher.oeffnen(ordner.resolve("erinnerungen.db").toString());
        offeneSpeicher.add(speicher);
        return speicher;
    }

    private static JsonNode nachricht(String text) throws Exception {
        int ende = text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ');
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"chat\":{\"id\":" + CHAT
                + ",\"type\":\"private\"},\"date\":1,\"text\":" + JSON.writeValueAsString(text)
                + ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + ende + "}]}}");
    }

    private static JsonNode klick(String daten, long nachrichtId) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"callback_query\":{\"id\":\"cb\",\"from\":{\"id\":7},"
                + "\"message\":{\"message_id\":" + nachrichtId + ",\"chat\":{\"id\":" + CHAT + ",\"type\":\"private\"}},"
                + "\"data\":\"" + daten + "\"}}");
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    @Test
    void eineOffeneErinnerungUeberlebtDenNeustartUndKommtZurRichtigenZeit() throws Exception {
        SqliteSpeicher vorher = datei();
        starten(vorher, new TestPlaner()).behandle(nachricht("/erinnere 10m Tee"));
        vorher.close(); // Programm beendet: alle Timer sind weg

        uhr.jetzt = START.plusSeconds(120); // zwei Minuten später wieder gestartet
        TestPlaner neuerPlaner = new TestPlaner();
        ErinnerungsBot bot = starten(datei(), neuerPlaner);
        int wieder = bot.wiederaufnehmen();

        assertEquals(1, wieder);
        assertEquals(List.of(Duration.ofMinutes(8)), neuerPlaner.verzoegerungen, "noch 8 von 10 Minuten");

        neuerPlaner.aufgaben.get(0).run();
        assertEquals(100, letzte("deleteMessage").get("message_id").asLong(), "der alte Status wird gelöscht");
        assertEquals("⏰ Erinnerung: Tee", letzte("sendMessage").get("text").asText());
    }

    @Test
    void verpassteErinnerungenKommenSofortMitHinweis() throws Exception {
        SqliteSpeicher vorher = datei();
        starten(vorher, new TestPlaner()).behandle(nachricht("/erinnere 10m Tee"));
        vorher.close();

        uhr.jetzt = START.plus(Duration.ofHours(3)); // der Bot war drei Stunden aus
        TestPlaner planer = new TestPlaner();
        ErinnerungsBot bot = starten(datei(), planer);
        bot.wiederaufnehmen();

        assertEquals(List.of(Duration.ZERO), planer.verzoegerungen);
        planer.aufgaben.get(0).run();
        assertEquals("⏰ Erinnerung: Tee (verspätet)", letzte("sendMessage").get("text").asText());
    }

    @Test
    void geringeVerspaetungWirdNichtErwaehnt() throws Exception {
        SqliteSpeicher speicher = datei();
        TestPlaner planer = new TestPlaner();
        ErinnerungsBot bot = starten(speicher, planer);
        bot.behandle(nachricht("/erinnere 10s Tee"));

        uhr.jetzt = START.plusSeconds(10 + 30); // 30 Sekunden zu spät: unter der Toleranz
        planer.aufgaben.get(0).run();

        assertEquals("⏰ Erinnerung: Tee", letzte("sendMessage").get("text").asText());
    }

    @Test
    void abbrechenFunktioniertAuchNachDemNeustartMitDerAltenTaste() throws Exception {
        SqliteSpeicher vorher = datei();
        starten(vorher, new TestPlaner()).behandle(nachricht("/erinnere 10m Tee"));
        vorher.close();

        TestPlaner planer = new TestPlaner();
        ErinnerungsBot bot = starten(datei(), planer);
        bot.wiederaufnehmen();
        bot.behandle(klick("abbrechen:1", 100)); // die Nachricht mit der Taste gab es schon vor dem Neustart

        assertEquals("❌ Abgebrochen: Tee", letzte("editMessageText").get("text").asText());
        assertTrue(planer.abgesagt.get(0)[0], "der neu geplante Timer wurde abgesagt");
        assertEquals(0, datei().alleOffenen().size());
    }

    @Test
    void ausgeloesteErinnerungenWartenNachDemNeustartAufErledigt() throws Exception {
        SqliteSpeicher vorher = datei();
        TestPlaner planer1 = new TestPlaner();
        ErinnerungsBot bot1 = starten(vorher, planer1);
        bot1.behandle(nachricht("/erinnere 10m Tee"));
        planer1.aufgaben.get(0).run(); // fällig, Erinnerung (message_id 101) gesendet
        vorher.close();

        ErinnerungsBot bot2 = starten(datei(), new TestPlaner());
        bot2.wiederaufnehmen();
        bot2.behandle(klick("erledigt:1", 101));

        assertEquals("✅ Erledigt: Tee", letzte("editMessageText").get("text").asText());
    }

    @Test
    void zeilenOhneStatusnachrichtWerdenBeimWiederaufnehmenVerworfen() throws Exception {
        SqliteSpeicher speicher = datei();
        speicher.anlegen(CHAT, "Waise", 0, START.plusSeconds(60)); // Abbruch zwischen Speichern und Senden
        speicher.anlegen(CHAT, "echt", 7, START.plusSeconds(60));
        TestPlaner planer = new TestPlaner();

        int wieder = starten(speicher, planer).wiederaufnehmen();

        assertEquals(1, wieder);
        assertEquals(1, planer.aufgaben.size());
        assertEquals(List.of("echt"), speicher.alleOffenen().stream().map(Erinnerung::text).toList());
    }

    @Test
    void scheitertDasSendenDesStatusWirdDieErinnerungNichtGespeichert() throws Exception {
        SqliteSpeicher speicher = datei();
        TestPlaner planer = new TestPlaner();
        ErinnerungsBot bot = starten(speicher, planer);
        fake.failNext("sendMessage", 403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}");

        assertThrows(TelegramException.class, () -> bot.behandle(nachricht("/erinnere 10m Tee")));

        assertEquals(0, speicher.anzahlOffen(CHAT), "zurückgerollt");
        assertEquals(0, planer.aufgaben.size());
    }

    @Test
    void aufgeloesteIdsWerdenNichtWiederverwendet() throws Exception {
        SqliteSpeicher speicher = datei();
        ErinnerungsBot bot = starten(speicher, new TestPlaner());
        bot.behandle(nachricht("/erinnere 10m Eins"));
        bot.behandle(klick("abbrechen:1", 100));

        bot.behandle(nachricht("/erinnere 10m Zwei"));

        assertEquals(2, speicher.alleOffenen().getFirst().id());
        bot.behandle(klick("abbrechen:1", 100)); // alte Taste von "Eins" darf "Zwei" nicht treffen
        assertEquals(1, speicher.anzahlOffen(CHAT));
        assertFalse(letzte("answerCallbackQuery").get("text").asText().equals("Abgebrochen"));
    }
}
