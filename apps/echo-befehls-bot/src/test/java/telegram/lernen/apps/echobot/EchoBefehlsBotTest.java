package telegram.lernen.apps.echobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class EchoBefehlsBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOT = "fake_bot";

    private FakeTelegramServer fake;
    private EchoBefehlsBot bot;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("answerCallbackQuery", "true");
        fake.onMethod("setMyCommands", "true");
        bot = new EchoBefehlsBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), BOT);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    // --- Hilfen: Updates wie von Telegram

    /** Text mit Befehl am Anfang bekommt wie bei Telegram eine bot_command-Entität. */
    private static JsonNode nachricht(long chatId, String text) throws Exception {
        String entities = "";
        if (text.startsWith("/")) {
            int ende = text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ');
            entities = ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + ende + "}]";
        }
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"},\"date\":1,\"text\":"
                + JSON.writeValueAsString(text) + entities + "}}");
    }

    private static JsonNode klick(long nutzer, String id, String daten) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"callback_query\":{\"id\":\"" + id + "\",\"from\":{\"id\":" + nutzer
                + ",\"is_bot\":false,\"first_name\":\"T\"},\"chat_instance\":\"c\",\"data\":\"" + daten + "\"}}");
    }

    private JsonNode letzteAnfrage(String methode) throws Exception {
        List<RecordedRequest> alle = fake.requests(methode);
        return JSON.readTree(alle.getLast().body());
    }

    // --- Befehle

    @Test
    void startBegruesst() throws Exception {
        bot.behandle(nachricht(42, "/start"));

        JsonNode gesendet = letzteAnfrage("sendMessage");
        assertEquals(42, gesendet.get("chat_id").asLong());
        assertTrue(gesendet.get("text").asText().startsWith("Hallo!"));
        assertFalse(gesendet.get("text").asText().contains("Parameter"));
    }

    @Test
    void startMitDeepLinkParameterNenntDenParameter() throws Exception {
        bot.behandle(nachricht(42, "/start airplane"));

        assertTrue(letzteAnfrage("sendMessage").get("text").asText().contains("\"airplane\""));
    }

    @Test
    void hilfeListetAlleBefehle() throws Exception {
        bot.behandle(nachricht(42, "/hilfe"));

        String text = letzteAnfrage("sendMessage").get("text").asText();
        for (EchoBefehlsBot.BefehlInfo befehl : EchoBefehlsBot.BEFEHLE) {
            assertTrue(text.contains("/" + befehl.name() + " - " + befehl.beschreibung()), text);
        }
    }

    @Test
    void helpIstEinAliasFuerHilfe() throws Exception {
        bot.behandle(nachricht(42, "/help"));

        assertTrue(letzteAnfrage("sendMessage").get("text").asText().contains("/start - Bot starten"));
    }

    @Test
    void echoSendetDenTextNachDemBefehlZurueck() throws Exception {
        bot.behandle(nachricht(42, "/echo  Hallo   Welt "));

        assertEquals("Hallo   Welt", letzteAnfrage("sendMessage").get("text").asText());
    }

    @Test
    void echoOhneTextZeigtDieBenutzung() throws Exception {
        bot.behandle(nachricht(42, "/echo"));

        assertEquals("Benutzung: /echo <Text>", letzteAnfrage("sendMessage").get("text").asText());
    }

    @Test
    void unbekannterBefehlGibtEinenHinweis() throws Exception {
        bot.behandle(nachricht(42, "/gibtsnicht"));

        String text = letzteAnfrage("sendMessage").get("text").asText();
        assertTrue(text.contains("/gibtsnicht"));
        assertTrue(text.contains("/hilfe"));
    }

    @Test
    void befehleSindNichtGrossKleinEmpfindlichUndMitBotnameErlaubt() throws Exception {
        bot.behandle(nachricht(42, "/HILFE@Fake_Bot"));

        assertTrue(letzteAnfrage("sendMessage").get("text").asText().contains("/start - Bot starten"));
    }

    @Test
    void befehleAnAndereBotsWerdenIgnoriert() throws Exception {
        bot.behandle(nachricht(-100, "/hilfe@anderer_bot"));

        assertEquals(0, fake.requests("sendMessage").size());
    }

    // --- Echo

    @Test
    void normalerTextWirdGespiegelt() throws Exception {
        bot.behandle(nachricht(42, "Hallo <b>Welt</b> & mehr"));

        JsonNode gesendet = letzteAnfrage("sendMessage");
        assertEquals("Hallo <b>Welt</b> & mehr", gesendet.get("text").asText());
        assertFalse(gesendet.has("parse_mode"), "ohne parse_mode, also keine Formatierung");
    }

    @Test
    void nachrichtenOhneTextBleibenUnbeantwortet() throws Exception {
        bot.behandle(JSON.readTree("{\"update_id\":3,\"message\":{\"message_id\":6,\"chat\":{\"id\":42,"
                + "\"type\":\"private\"},\"date\":1,\"sticker\":{\"file_id\":\"x\"}}}"));

        assertEquals(0, fake.requests("sendMessage").size());
    }

    // --- Tastaturen

    @Test
    void menueSendetEineInlineTastaturMitDreiZeilen() throws Exception {
        bot.behandle(nachricht(42, "/menue"));

        JsonNode tasten = letzteAnfrage("sendMessage").path("reply_markup").path("inline_keyboard");
        assertEquals(3, tasten.size());
        assertEquals("zaehlen", tasten.get(0).get(0).get("callback_data").asText());
        assertEquals("reset", tasten.get(0).get(1).get("callback_data").asText());
        assertEquals("hilfe", tasten.get(1).get(0).get("callback_data").asText());
        assertEquals("https://core.telegram.org/bots/api", tasten.get(2).get(0).get("url").asText());
    }

    @Test
    void alleCallbackDatenSindKurzGenug() throws Exception {
        bot.behandle(nachricht(42, "/menue"));

        for (JsonNode zeile : letzteAnfrage("sendMessage").path("reply_markup").path("inline_keyboard")) {
            for (JsonNode taste : zeile) {
                assertTrue(taste.path("callback_data").asText("").getBytes().length <= 64);
            }
        }
    }

    @Test
    void tastaturBlendetEineAntwortTastaturEinUndAusWiederAus() throws Exception {
        bot.behandle(nachricht(42, "/tastatur"));
        JsonNode ein = letzteAnfrage("sendMessage").path("reply_markup");
        assertEquals("/hilfe", ein.path("keyboard").get(0).get(0).get("text").asText());
        assertTrue(ein.get("resize_keyboard").asBoolean());

        bot.behandle(nachricht(42, "/aus"));
        assertTrue(letzteAnfrage("sendMessage").path("reply_markup").get("remove_keyboard").asBoolean());
    }

    // --- Callbacks

    @Test
    void zaehlenZaehltProNutzerUndBeantwortetJedenKlick() throws Exception {
        bot.behandle(klick(7, "q1", "zaehlen"));
        bot.behandle(klick(7, "q2", "zaehlen"));
        bot.behandle(klick(8, "q3", "zaehlen"));

        List<RecordedRequest> antworten = fake.requests("answerCallbackQuery");
        assertEquals(3, antworten.size());
        assertTrue(antworten.get(0).bodyContains("\"callback_query_id\":\"q1\""));
        assertTrue(antworten.get(0).bodyContains("\"text\":\"Zähler: 1\""));
        assertTrue(antworten.get(1).bodyContains("\"text\":\"Zähler: 2\""));
        assertTrue(antworten.get(2).bodyContains("\"text\":\"Zähler: 1\""), "anderer Nutzer, eigener Zähler");
    }

    @Test
    void resetSetztDenZaehlerZurueck() throws Exception {
        bot.behandle(klick(7, "q1", "zaehlen"));
        bot.behandle(klick(7, "q2", "reset"));
        bot.behandle(klick(7, "q3", "zaehlen"));

        List<RecordedRequest> antworten = fake.requests("answerCallbackQuery");
        assertTrue(antworten.get(1).bodyContains("Zähler zurückgesetzt"));
        assertTrue(antworten.get(2).bodyContains("\"text\":\"Zähler: 1\""));
    }

    @Test
    void hilfeKlickZeigtEinenAlertImZeichenLimit() throws Exception {
        bot.behandle(klick(7, "q1", "hilfe"));

        JsonNode antwort = letzteAnfrage("answerCallbackQuery");
        assertTrue(antwort.get("show_alert").asBoolean());
        assertTrue(antwort.get("text").asText().length() <= 200);
    }

    @Test
    void veralteteTastenWerdenTrotzdemBeantwortet() throws Exception {
        bot.behandle(klick(7, "q1", "gibt-es-nicht-mehr"));

        JsonNode antwort = letzteAnfrage("answerCallbackQuery");
        assertEquals("q1", antwort.get("callback_query_id").asText());
        assertEquals("Diese Taste ist veraltet.", antwort.get("text").asText());
    }

    // --- Anmeldung der Befehle

    @Test
    void befehleRegistrierenSendetGueltigeBefehle() throws Exception {
        bot.befehleRegistrieren();

        JsonNode befehle = letzteAnfrage("setMyCommands").get("commands");
        assertEquals(EchoBefehlsBot.BEFEHLE.size(), befehle.size());
        for (JsonNode befehl : befehle) {
            assertTrue(befehl.get("command").asText().matches("[a-z0-9_]{1,32}"), befehl.toString());
            int laenge = befehl.get("description").asText().length();
            assertTrue(laenge >= 1 && laenge <= 256);
        }
    }

    @Test
    void befehleSindEindeutigUndKurzhilfePasstInEinenAlert() {
        long verschiedene = EchoBefehlsBot.BEFEHLE.stream().map(EchoBefehlsBot.BefehlInfo::name).distinct().count();
        assertEquals(EchoBefehlsBot.BEFEHLE.size(), verschiedene);
        assertTrue(EchoBefehlsBot.kurzhilfe().length() <= 200);
    }

    // --- Mit echter Poller-Schleife

    @Test
    void ganzerLaufUeberDenPoller() throws Exception {
        fake.enqueueUpdate("message", nachricht(42, "/echo hi").get("message").toString());
        fake.enqueueUpdate("callback_query", klick(7, "q9", "zaehlen").get("callback_query").toString());
        AtomicReference<Throwable> fehler = new AtomicReference<>();
        UpdatePoller poller = new UpdatePoller(new TelegramClient("123456:GEHEIM", fake.baseUrl()),
                List.of("message", "callback_query"));
        Thread thread = new Thread(() -> {
            try {
                poller.laufen(bot.alsHandler());
            } catch (InterruptedException e) {
                // erwartetes Ende
            } catch (Throwable t) {
                fehler.set(t);
            }
        });
        thread.start();
        try {
            RecordedRequest gesendet = fake.awaitRequests("sendMessage", 1, Duration.ofSeconds(5)).get(0);
            assertTrue(gesendet.bodyContains("\"text\":\"hi\""));
            RecordedRequest beantwortet = fake.awaitRequests("answerCallbackQuery", 1, Duration.ofSeconds(5)).get(0);
            assertTrue(beantwortet.bodyContains("\"callback_query_id\":\"q9\""));
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
        assertEquals(null, fehler.get());
        assertTrue(fake.requests("getUpdates").getLast().bodyContains("\"allowed_updates\":[\"message\",\"callback_query\"]"));
    }
}
