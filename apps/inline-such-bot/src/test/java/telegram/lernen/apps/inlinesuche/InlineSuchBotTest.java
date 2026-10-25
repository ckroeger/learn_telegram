package telegram.lernen.apps.inlinesuche;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class InlineSuchBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeTelegramServer fake;
    private InlineSuchBot bot;

    private static Katalog katalog(int anzahl) {
        List<Eintrag> liste = new ArrayList<>();
        for (int i = 1; i <= anzahl; i++) {
            liste.add(new Eintrag("methode" + String.format("%02d", i), "Beschreibung " + i));
        }
        return new Katalog(liste);
    }

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("answerInlineQuery", "true");
        bot = botMit(katalog(25));
    }

    private InlineSuchBot botMit(Katalog katalog) {
        return new InlineSuchBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", katalog);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode abfrage(String text, String offset) throws Exception {
        return JSON.readTree("{\"update_id\":1,\"inline_query\":{\"id\":\"iq1\",\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"query\":" + JSON.writeValueAsString(text) + ",\"offset\":\"" + offset + "\"}}");
    }

    private static JsonNode gewaehlt(String resultId) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"chosen_inline_result\":{\"result_id\":\"" + resultId + "\",\"from\":{\"id\":7,"
                + "\"is_bot\":false,\"first_name\":\"T\"},\"query\":\"x\"}}");
    }

    private static JsonNode nachricht(String text) throws Exception {
        String entitaet = text.startsWith("/")
                ? ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + (text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ')) + "}]"
                : "";
        return JSON.readTree("{\"update_id\":3,\"message\":{\"message_id\":5,\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"chat\":{\"id\":42,\"type\":\"private\"},\"date\":1,\"text\":\"" + text + "\"" + entitaet + "}}");
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    // --- Suche

    @Test
    void ergebnisseSindArtikelMitNameBeschreibungUndLink() throws Exception {
        bot.behandle(abfrage("methode03", ""));

        JsonNode antwort = letzte("answerInlineQuery");
        assertEquals("iq1", antwort.get("inline_query_id").asText());
        assertEquals(1, antwort.get("results").size());
        JsonNode artikel = antwort.at("/results/0");
        assertEquals("article", artikel.get("type").asText());
        assertEquals("methode03", artikel.get("id").asText());
        assertEquals("methode03", artikel.get("title").asText());
        assertEquals("Beschreibung 3", artikel.get("description").asText());
        assertEquals("methode03 - Beschreibung 3\nhttps://core.telegram.org/bots/api#methode03",
                artikel.at("/input_message_content/message_text").asText());
    }

    @Test
    void antwortSetztCacheZeit() throws Exception {
        bot.behandle(abfrage("methode03", ""));

        assertEquals(InlineSuchBot.CACHE_SEKUNDEN, letzte("answerInlineQuery").get("cache_time").asInt());
    }

    @Test
    void ohneTrefferGibtEsEineLeereListeUndEineHilfeTaste() throws Exception {
        bot.behandle(abfrage("gibtsnicht", ""));

        JsonNode antwort = letzte("answerInlineQuery");
        assertEquals(0, antwort.get("results").size());
        assertEquals("hilfe", antwort.at("/button/start_parameter").asText());
        assertTrue(antwort.at("/button/text").asText().startsWith("Nichts gefunden"));
        assertEquals("", antwort.get("next_offset").asText());
    }

    @Test
    void mitTreffernZeigtDieTasteDenDeepLinkSuche() throws Exception {
        bot.behandle(abfrage("methode01", ""));

        JsonNode taste = letzte("answerInlineQuery").get("button");
        assertEquals("suche", taste.get("start_parameter").asText());
        assertTrue(taste.get("start_parameter").asText().matches("[A-Za-z0-9_-]{1,64}"));
    }

    // --- Seiten

    @Test
    void ersteSeiteHatZehnErgebnisseUndEinNextOffset() throws Exception {
        bot.behandle(abfrage("methode", ""));

        JsonNode antwort = letzte("answerInlineQuery");
        assertEquals(10, antwort.get("results").size());
        assertEquals("methode01", antwort.at("/results/0/id").asText());
        assertEquals("10", antwort.get("next_offset").asText());
    }

    @Test
    void naechsteSeiteBeginntBeimOffsetUndEndetMitLeeremNextOffset() throws Exception {
        bot.behandle(abfrage("methode", "20"));

        JsonNode antwort = letzte("answerInlineQuery");
        assertEquals(5, antwort.get("results").size());
        assertEquals("methode21", antwort.at("/results/0/id").asText());
        assertEquals("", antwort.get("next_offset").asText());
    }

    @Test
    void genauEineVolleSeiteHatKeinNextOffset() throws Exception {
        botMit(katalog(10)).behandle(abfrage("methode", ""));

        assertEquals("", letzte("answerInlineQuery").get("next_offset").asText());
        assertEquals(10, letzte("answerInlineQuery").get("results").size());
    }

    @Test
    void ungueltigeOffsetsFuehrenZumAnfangOderZuEinerLeerenSeite() throws Exception {
        bot.behandle(abfrage("methode", "abc"));
        assertEquals("methode01", letzte("answerInlineQuery").at("/results/0/id").asText());
        bot.behandle(abfrage("methode", "-5"));
        assertEquals("methode01", letzte("answerInlineQuery").at("/results/0/id").asText());
        bot.behandle(abfrage("methode", "9999"));
        assertEquals(0, letzte("answerInlineQuery").get("results").size());
        assertEquals("", letzte("answerInlineQuery").get("next_offset").asText());
        bot.behandle(abfrage("methode", "99999999999"));
        assertEquals("methode01", letzte("answerInlineQuery").at("/results/0/id").asText(), "zu groß für int: Anfang");
    }

    // --- Beliebtheit

    @Test
    void leereAbfrageZeigtDenKatalogInReihenfolge() throws Exception {
        bot.behandle(abfrage("", ""));

        assertEquals("methode01", letzte("answerInlineQuery").at("/results/0/id").asText());
        assertEquals(10, letzte("answerInlineQuery").get("results").size());
    }

    @Test
    void gewaehlteErgebnisseRutschenBeiLeererAbfrageNachVorn() throws Exception {
        bot.behandle(gewaehlt("methode05"));
        bot.behandle(gewaehlt("methode07"));
        bot.behandle(gewaehlt("methode07"));
        bot.behandle(abfrage("", ""));

        JsonNode ergebnisse = letzte("answerInlineQuery").get("results");
        assertEquals("methode07", ergebnisse.get(0).get("id").asText());
        assertEquals("methode05", ergebnisse.get(1).get("id").asText());
        assertEquals("methode01", ergebnisse.get(2).get("id").asText());
    }

    @Test
    void auswahlOhneBekanntenEintragStoertNicht() throws Exception {
        bot.behandle(gewaehlt("unbekannt"));
        bot.behandle(abfrage("", ""));

        assertEquals("methode01", letzte("answerInlineQuery").at("/results/0/id").asText());
        assertEquals(0, fake.requests("sendMessage").size());
    }

    // --- Fehler bei der Antwort

    @Test
    void abgelehnteAntwortStopptDenBotNicht() throws Exception {
        fake.failNext("answerInlineQuery", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: query is too old\"}");

        bot.behandle(abfrage("methode01", ""));
        bot.behandle(abfrage("methode02", ""));

        assertEquals(2, fake.requests("answerInlineQuery").size());
    }

    // --- Chat

    @Test
    void startUndTextAntwortenMitHilfeUndInlineTasten() throws Exception {
        bot.behandle(nachricht("/start"));

        JsonNode gesendet = letzte("sendMessage");
        assertEquals(42, gesendet.get("chat_id").asLong());
        assertTrue(gesendet.get("text").asText().contains("@fake_bot"));
        JsonNode tasten = gesendet.at("/reply_markup/inline_keyboard/0");
        assertEquals("", tasten.get(0).get("switch_inline_query_current_chat").asText());
        assertEquals("", tasten.get(1).get("switch_inline_query").asText());
    }

    @Test
    void startMitParameterHilfeErklaertDieLeereSuche() throws Exception {
        bot.behandle(nachricht("/start hilfe"));
        assertTrue(letzte("sendMessage").get("text").asText().startsWith("Dazu habe ich nichts gefunden"));

        bot.behandle(nachricht("/start suche"));
        assertFalse(letzte("sendMessage").get("text").asText().startsWith("Dazu"));
    }

    @Test
    void befehleAnAndereBotsWerdenIgnoriert() throws Exception {
        bot.behandle(nachricht("/start@anderer_bot"));

        assertEquals(0, fake.requests("sendMessage").size());
    }

    // --- Mit echter Poller-Schleife

    @Test
    void ganzerLaufUeberDenPoller() throws Exception {
        fake.enqueueUpdate("inline_query", abfrage("methode03", "").get("inline_query").toString());
        AtomicReference<Throwable> fehler = new AtomicReference<>();
        UpdatePoller poller = new UpdatePoller(new TelegramClient("123456:GEHEIM", fake.baseUrl()),
                List.of("message", "inline_query", "chosen_inline_result"));
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
            RecordedRequest antwort = fake.awaitRequests("answerInlineQuery", 1, Duration.ofSeconds(5)).get(0);
            assertTrue(antwort.bodyContains("\"id\":\"methode03\""));
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
        assertEquals(null, fehler.get());
        assertTrue(fake.requests("getUpdates").getLast()
                .bodyContains("\"allowed_updates\":[\"message\",\"inline_query\",\"chosen_inline_result\"]"));
    }
}
