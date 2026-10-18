package telegram.lernen.nachrichten;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class NachrichtenTest {

    private static final String TOKEN = "123456:GEHEIM";

    private static Nachrichten mit(FakeTelegramServer fake) {
        return new Nachrichten(new BotApi(TOKEN, fake.baseUrl()));
    }

    @Test
    void sendetEinfacheNachrichtUndLiefertMessageId() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            JsonNode gesendet = mit(fake).senden(42, "Hallo");

            assertEquals(100, gesendet.get("message_id").asLong());
            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertTrue(aufruf.bodyContains("\"chat_id\":42"));
            assertTrue(aufruf.bodyContains("\"text\":\"Hallo\""));
            assertFalse(aufruf.bodyContains("parse_mode"));
        }
    }

    @Test
    void sendetHtmlMitParseMode() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            mit(fake).sendenHtml(42, "<b>fett</b>");

            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertTrue(aufruf.bodyContains("\"parse_mode\":\"HTML\""));
            assertTrue(aufruf.bodyContains("\"text\":\"<b>fett</b>\""));
        }
    }

    @Test
    void htmlEscapeErsetztNurDieDreiZeichen() {
        assertEquals("a &lt; b &amp;&amp; c &gt; d", Nachrichten.htmlEscape("a < b && c > d"));
        assertEquals("&amp;lt;", Nachrichten.htmlEscape("&lt;"));
        assertEquals("\"Anführungszeichen\" bleiben", Nachrichten.htmlEscape("\"Anführungszeichen\" bleiben"));
    }

    @Test
    void antwortenNutztReplyParameters() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            mit(fake).antworten(42, 7, "Antwort");

            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertTrue(aufruf.bodyContains("\"reply_parameters\":{\"message_id\":7}"), aufruf.body());
        }
    }

    @Test
    void diskretSetztDieOptionen() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            mit(fake).diskret(42, "psst https://example.com");

            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertTrue(aufruf.bodyContains("\"disable_notification\":true"));
            assertTrue(aufruf.bodyContains("\"protect_content\":true"));
            assertTrue(aufruf.bodyContains("\"link_preview_options\":{\"is_disabled\":true}"), aufruf.body());
        }
    }

    @Test
    void tippenSendetDieChatAktionTyping() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("sendChatAction", "true");

            mit(fake).tippen(42);

            RecordedRequest aufruf = fake.requests("sendChatAction").get(0);
            assertTrue(aufruf.bodyContains("\"action\":\"typing\""));
            assertTrue(aufruf.bodyContains("\"chat_id\":42"));
        }
    }

    @Test
    void weiterleitenUndKopierenSendenQuelleUndZiel() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("forwardMessage", "{\"message_id\":55,\"chat\":{\"id\":1,\"type\":\"private\"},\"date\":1}");
            fake.onMethod("copyMessage", "{\"message_id\":99}");
            Nachrichten nachrichten = mit(fake);

            nachrichten.weiterleiten(1, 2, 3);
            long neueId = nachrichten.kopieren(1, 2, 3);

            assertEquals(99, neueId);
            for (String methode : List.of("forwardMessage", "copyMessage")) {
                RecordedRequest aufruf = fake.requests(methode).get(0);
                assertTrue(aufruf.bodyContains("\"chat_id\":1"));
                assertTrue(aufruf.bodyContains("\"from_chat_id\":2"));
                assertTrue(aufruf.bodyContains("\"message_id\":3"));
            }
        }
    }

    @Test
    void telegramFehlerWerdenAlsExceptionWeitergegeben() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.failNext("sendMessage", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is too long\"}");

            TelegramException fehler = assertThrows(TelegramException.class, () -> mit(fake).senden(42, "x"));
            assertEquals(400, fehler.errorCode());
            assertTrue(fehler.getMessage().contains("message is too long"));
            assertFalse(fehler.getMessage().contains("GEHEIM"));
        }
    }

    @Test
    void sendenLangSendetMehrereNachrichtenImLimit() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            String zeile = "x".repeat(3000);
            String text = String.join("\n", zeile, zeile, zeile);

            List<JsonNode> gesendet = mit(fake).sendenLang(42, text);

            assertEquals(3, gesendet.size());
            assertEquals(3, fake.requests("sendMessage").size());
        }
    }

    @Test
    void uebungLoesungEscaptDenCode() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            UebungLoesung.codeSenden(mit(fake), 42, "if (a < b && c > d) {}");

            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertTrue(aufruf.bodyContains("\"parse_mode\":\"HTML\""));
            assertTrue(aufruf.bodyContains(
                    "<pre><code class=\\\"language-java\\\">if (a &lt; b &amp;&amp; c &gt; d) {}</code></pre>"),
                    aufruf.body());
        }
    }
}
