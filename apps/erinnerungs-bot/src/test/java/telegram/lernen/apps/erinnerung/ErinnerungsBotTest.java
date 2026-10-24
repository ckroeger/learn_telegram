package telegram.lernen.apps.erinnerung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class ErinnerungsBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CHAT = 42;

    /** Planer, der die Zeit nicht abwartet: Die Tests lösen Aufgaben selbst aus. */
    private static class TestPlaner implements Planer {
        record Aufgabe(Duration verzoegerung, Runnable aufgabe, boolean[] abgesagt) { }

        final List<Aufgabe> aufgaben = new ArrayList<>();

        @Override
        public Runnable planen(Duration verzoegerung, Runnable aufgabe) {
            boolean[] abgesagt = new boolean[1];
            aufgaben.add(new Aufgabe(verzoegerung, aufgabe, abgesagt));
            return () -> abgesagt[0] = true;
        }

        /** Löst die Aufgabe aus, wenn sie nicht abgesagt ist, wie es der echte Planer tut. */
        void ausloesen(int index) {
            Aufgabe a = aufgaben.get(index);
            if (!a.abgesagt()[0]) {
                a.aufgabe().run();
            }
        }
    }

    private FakeTelegramServer fake;
    private TestPlaner planer;
    private ErinnerungsBot bot;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        for (String methode : List.of("answerCallbackQuery", "editMessageText", "deleteMessage", "setMyCommands")) {
            fake.onMethod(methode, "true");
        }
        planer = new TestPlaner();
        Clock uhr = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
        bot = new ErinnerungsBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", planer, uhr);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode nachricht(String text) throws Exception {
        String entities = "";
        if (text.startsWith("/")) {
            int ende = text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ');
            entities = ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + ende + "}]";
        }
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"chat\":{\"id\":" + CHAT
                + ",\"type\":\"private\"},\"date\":1,\"text\":" + JSON.writeValueAsString(text) + entities + "}}");
    }

    private static JsonNode klick(String daten, long chat, long nachrichtId) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"callback_query\":{\"id\":\"cb" + nachrichtId + "\",\"from\":{\"id\":7},"
                + "\"message\":{\"message_id\":" + nachrichtId + ",\"chat\":{\"id\":" + chat + ",\"type\":\"private\"}},"
                + "\"data\":\"" + daten + "\"}}");
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    // --- /erinnere

    @Test
    void erinnereSendetStatusMitAbbrechenTasteUndPlantDieZeit() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee ziehen lassen"));

        JsonNode status = letzte("sendMessage");
        assertEquals("⏳ Erinnerung in 10 Minuten: Tee ziehen lassen", status.get("text").asText());
        assertEquals("abbrechen:1", status.path("reply_markup").path("inline_keyboard").get(0).get(0)
                .get("callback_data").asText());
        assertEquals(1, planer.aufgaben.size());
        assertEquals(Duration.ofMinutes(10), planer.aufgaben.get(0).verzoegerung());
    }

    @Test
    void ungueltigeEingabenZeigenDieBenutzungUndPlanenNichts() throws Exception {
        for (String eingabe : List.of("/erinnere", "/erinnere abc Tee", "/erinnere 10m", "/erinnere 10m   ",
                "/erinnere 0s x", "/erinnere 8d x")) {
            bot.behandle(nachricht(eingabe));
            assertTrue(letzte("sendMessage").get("text").asText().startsWith("Benutzung:"), eingabe);
        }
        assertEquals(0, planer.aufgaben.size());
    }

    @Test
    void hoechstensZwanzigOffeneErinnerungenProChat() throws Exception {
        for (int i = 0; i < ErinnerungsBot.MAX_OFFEN; i++) {
            bot.behandle(nachricht("/erinnere 1h Nr " + i));
        }
        bot.behandle(nachricht("/erinnere 1h eine zu viel"));

        assertEquals(ErinnerungsBot.MAX_OFFEN, planer.aufgaben.size());
        assertTrue(letzte("sendMessage").get("text").asText().contains("schon 20 offene"));
    }

    @Test
    void listeZeigtOffeneErinnerungenMitRestzeit() throws Exception {
        bot.behandle(nachricht("/liste"));
        assertEquals("Keine offenen Erinnerungen.", letzte("sendMessage").get("text").asText());

        bot.behandle(nachricht("/erinnere 2h Meeting"));
        bot.behandle(nachricht("/erinnere 30m Tee"));
        bot.behandle(nachricht("/liste"));

        assertEquals("Offene Erinnerungen:\n- Tee (in 30 Minuten)\n- Meeting (in 2 Stunden)",
                letzte("sendMessage").get("text").asText());
    }

    // --- Fälligkeit

    @Test
    void faelligLoeschtDenStatusUndSendetDieErinnerung() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        long statusId = 100; // erste vom Fake vergebene message_id

        planer.ausloesen(0);

        JsonNode geloescht = letzte("deleteMessage");
        assertEquals(CHAT, geloescht.get("chat_id").asLong());
        assertEquals(statusId, geloescht.get("message_id").asLong());
        JsonNode erinnerung = letzte("sendMessage");
        assertEquals("⏰ Erinnerung: Tee", erinnerung.get("text").asText());
        JsonNode tasten = erinnerung.path("reply_markup").path("inline_keyboard").get(0);
        assertEquals("erledigt:1", tasten.get(0).get("callback_data").asText());
        assertEquals("spaeter:1", tasten.get(1).get("callback_data").asText());

        bot.behandle(nachricht("/liste"));
        assertEquals("Keine offenen Erinnerungen.", letzte("sendMessage").get("text").asText());
    }

    @Test
    void scheitertDasLoeschenMitEinem400WirdTrotzdemErinnert() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        fake.failNext("deleteMessage", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message to delete not found\"}");

        planer.ausloesen(0);

        assertEquals("⏰ Erinnerung: Tee", letzte("sendMessage").get("text").asText());
    }

    // --- Tasten

    @Test
    void abbrechenBearbeitetDenStatusEntferntDieTastenUndSagtDieZeitAb() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));

        bot.behandle(klick("abbrechen:1", CHAT, 100));

        JsonNode bearbeitet = letzte("editMessageText");
        assertEquals(100, bearbeitet.get("message_id").asLong());
        assertEquals("❌ Abgebrochen: Tee", bearbeitet.get("text").asText());
        assertFalse(bearbeitet.has("reply_markup"), "ohne reply_markup verschwinden die Tasten");
        assertTrue(planer.aufgaben.get(0).abgesagt()[0]);
        assertTrue(letzte("answerCallbackQuery").get("text").asText().equals("Abgebrochen"));

        planer.ausloesen(0); // abgesagt: darf nichts mehr senden
        assertEquals(0, fake.requests("deleteMessage").size());
        assertEquals(1, fake.requests("sendMessage").size());
    }

    @Test
    void abbrechenUndFaelligkeitGleichzeitigWirdNurEinmalAusgefuehrt() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        Runnable faellig = planer.aufgaben.get(0).aufgabe();

        faellig.run();
        bot.behandle(klick("abbrechen:1", CHAT, 100));

        assertEquals(0, fake.requests("editMessageText").size(), "schon ausgelöst: nichts mehr abzubrechen");
        assertEquals("Diese Erinnerung gibt es nicht mehr.", letzte("answerCallbackQuery").get("text").asText());
    }

    @Test
    void erledigtBearbeitetDieErinnerung() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        planer.ausloesen(0);

        bot.behandle(klick("erledigt:1", CHAT, 101));

        JsonNode bearbeitet = letzte("editMessageText");
        assertEquals(101, bearbeitet.get("message_id").asLong());
        assertEquals("✅ Erledigt: Tee", bearbeitet.get("text").asText());
        assertFalse(bearbeitet.has("reply_markup"));

        bot.behandle(klick("erledigt:1", CHAT, 101)); // doppelter Klick
        assertEquals(1, fake.requests("editMessageText").size());
        assertEquals("Diese Erinnerung gibt es nicht mehr.", letzte("answerCallbackQuery").get("text").asText());
    }

    @Test
    void spaeterVerwandeltDieErinnerungWiederInEinenStatusUndPlantNeu() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        planer.ausloesen(0);

        bot.behandle(klick("spaeter:1", CHAT, 101));

        JsonNode bearbeitet = letzte("editMessageText");
        assertEquals(101, bearbeitet.get("message_id").asLong());
        assertEquals("⏳ Erinnerung in 5 Minuten: Tee", bearbeitet.get("text").asText());
        assertEquals("abbrechen:1", bearbeitet.path("reply_markup").path("inline_keyboard").get(0).get(0)
                .get("callback_data").asText());
        assertEquals(Duration.ofMinutes(5), planer.aufgaben.get(1).verzoegerung());

        // Die zweite Fälligkeit löscht die bearbeitete Nachricht (101) und erinnert noch einmal.
        planer.ausloesen(1);
        assertEquals(101, letzte("deleteMessage").get("message_id").asLong());
        assertEquals("⏰ Erinnerung: Tee", letzte("sendMessage").get("text").asText());
    }

    @Test
    void klicksAusEinemAnderenChatWerdenNichtAusgefuehrt() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));

        bot.behandle(klick("abbrechen:1", 999, 100));

        assertEquals(0, fake.requests("editMessageText").size());
        assertFalse(planer.aufgaben.get(0).abgesagt()[0]);
        assertEquals("Diese Erinnerung gibt es nicht mehr.", letzte("answerCallbackQuery").get("text").asText());
    }

    @Test
    void unbekannteOderKaputteTastenWerdenTrotzdemBeantwortet() throws Exception {
        bot.behandle(klick("abbrechen:abc", CHAT, 100));
        bot.behandle(klick("quatsch", CHAT, 100));
        bot.behandle(klick("abbrechen:99", CHAT, 100));

        List<RecordedRequest> antworten = fake.requests("answerCallbackQuery");
        assertEquals(3, antworten.size());
        assertTrue(antworten.get(1).bodyContains("veraltet"));
        assertEquals(0, fake.requests("editMessageText").size());
    }

    // --- Fehlerbehandlung beim Bearbeiten

    @Test
    void einFehler400BeimBearbeitenWirdIgnoriertAndereFehlerNicht() throws Exception {
        bot.behandle(nachricht("/erinnere 10m Tee"));
        fake.failNext("editMessageText", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is not modified\"}");
        bot.behandle(klick("abbrechen:1", CHAT, 100)); // kein Fehler, trotz 400
        assertEquals("Abgebrochen", letzte("answerCallbackQuery").get("text").asText());

        bot.behandle(nachricht("/erinnere 10m Kaffee"));
        fake.failNext("editMessageText", 403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}");
        assertThrows(TelegramException.class, () -> bot.behandle(klick("abbrechen:2", CHAT, 101)));
    }

    // --- Befehle

    @Test
    void hilfeUndUnbekanntesUndNormalerText() throws Exception {
        bot.behandle(nachricht("/hilfe"));
        assertTrue(letzte("sendMessage").get("text").asText().contains("/erinnere <Zeit> <Text>"));

        bot.behandle(nachricht("/quatsch"));
        assertTrue(letzte("sendMessage").get("text").asText().contains("/hilfe"));

        bot.behandle(nachricht("Hallo"));
        assertTrue(letzte("sendMessage").get("text").asText().startsWith("Ich verstehe nur Befehle"));

        int vorher = fake.requests("sendMessage").size();
        bot.behandle(nachricht("/hilfe@anderer_bot"));
        assertEquals(vorher, fake.requests("sendMessage").size(), "Befehle an andere Bots bleiben unbeantwortet");
    }

    @Test
    void befehleRegistrierenMeldetDreiGueltigeBefehle() throws Exception {
        bot.befehleRegistrieren();

        JsonNode befehle = letzte("setMyCommands").get("commands");
        assertEquals(3, befehle.size());
        for (JsonNode befehl : befehle) {
            assertTrue(befehl.get("command").asText().matches("[a-z0-9_]{1,32}"));
        }
    }

    // --- Mit dem echten Planer

    @Test
    void einSekundenErinnerungKommtMitDemEchtenPlaner() throws Exception {
        ErinnerungsBot echt = new ErinnerungsBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot",
                Planer.echt(), Clock.systemUTC());

        echt.behandle(nachricht("/erinnere 1s Tee"));
        List<RecordedRequest> gesendet = fake.awaitRequests("sendMessage", 2, Duration.ofSeconds(5));

        assertTrue(gesendet.get(0).bodyContains("⏳ Erinnerung in 1 Sekunde: Tee"));
        assertTrue(gesendet.get(1).bodyContains("⏰ Erinnerung: Tee"));
        assertEquals(1, fake.requests("deleteMessage").size());
    }
}
