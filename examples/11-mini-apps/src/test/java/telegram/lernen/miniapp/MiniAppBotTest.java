package telegram.lernen.miniapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class MiniAppBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://zufall.trycloudflare.com";

    private FakeTelegramServer fake;
    private MiniAppBot bot;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("setChatMenuButton", "true");
        bot = new MiniAppBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", URL);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode nachricht(String text) throws Exception {
        String entitaet = text.startsWith("/")
                ? ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + (text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ')) + "}]"
                : "";
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"from\":{\"id\":7,\"is_bot\":false,\"first_name\":\"T\"},"
                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"date\":1,\"text\":" + JSON.writeValueAsString(text) + entitaet + "}}");
    }

    private static JsonNode webAppDaten(String daten) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"message\":{\"message_id\":6,\"from\":{\"id\":7,\"is_bot\":false,\"first_name\":\"T\"},"
                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"date\":1,\"web_app_data\":{\"data\":" + JSON.writeValueAsString(daten)
                + ",\"button_text\":\"Formular öffnen\"}}}");
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    @Test
    void menueSetztEineWebAppTasteFuerAlleNutzer() throws Exception {
        bot.menueSetzen();

        JsonNode anfrage = letzte("setChatMenuButton");
        assertEquals("web_app", anfrage.at("/menu_button/type").asText());
        assertEquals("Mini App", anfrage.at("/menu_button/text").asText());
        assertEquals(URL, anfrage.at("/menu_button/web_app/url").asText());
        assertFalse(anfrage.has("chat_id"), "ohne chat_id gilt sie für alle");
    }

    @Test
    void startZeigtEineInlineTasteDieDieMiniAppOeffnet() throws Exception {
        bot.behandle(nachricht("/start"));

        JsonNode gesendet = letzte("sendMessage");
        assertEquals(42, gesendet.get("chat_id").asLong());
        assertEquals(URL, gesendet.at("/reply_markup/inline_keyboard/0/0/web_app/url").asText());
    }

    @Test
    void formularZeigtEineAntwortTasteMitWebApp() throws Exception {
        bot.behandle(nachricht("/formular"));

        assertEquals(URL, letzte("sendMessage").at("/reply_markup/keyboard/0/0/web_app/url").asText());
    }

    @Test
    void ausBlendetDieTastaturAus() throws Exception {
        bot.behandle(nachricht("/aus"));

        assertTrue(letzte("sendMessage").at("/reply_markup/remove_keyboard").asBoolean());
    }

    @Test
    void webAppDatenWerdenAlsTextZurueckgespiegelt() throws Exception {
        bot.behandle(webAppDaten("{\"text\":\"Hallo Bot!\"}"));

        JsonNode gesendet = letzte("sendMessage");
        assertEquals("Die Mini App hat gesendet: {\"text\":\"Hallo Bot!\"}", gesendet.get("text").asText());
        assertFalse(gesendet.has("parse_mode"));
        assertEquals(42, gesendet.get("chat_id").asLong());
    }

    @Test
    void sehrLangeWebAppDatenWerdenGekuerzt() throws Exception {
        bot.behandle(webAppDaten("x".repeat(4000)));

        assertEquals("Die Mini App hat gesendet: ".length() + MiniAppBot.MAX_ANZEIGE + 1, letzte("sendMessage").get("text").asText().length());
    }

    @Test
    void befehleAnAndereBotsUndNormalerTextWerdenIgnoriert() throws Exception {
        bot.behandle(nachricht("/start@anderer_bot"));
        bot.behandle(nachricht("hallo"));
        bot.behandle(JSON.readTree("{\"update_id\":3,\"callback_query\":{\"id\":\"1\"}}"));

        assertEquals(0, fake.requests("sendMessage").size());
    }

    @Test
    void ohneHttpsAdresseStartetDerBotNicht() {
        TelegramClient client = new TelegramClient("123456:GEHEIM", fake.baseUrl());

        assertThrows(IllegalArgumentException.class, () -> new MiniAppBot(client, "b", "http://localhost:8080"));
        assertThrows(IllegalArgumentException.class, () -> new MiniAppBot(client, "b", "localhost"));
    }
}
