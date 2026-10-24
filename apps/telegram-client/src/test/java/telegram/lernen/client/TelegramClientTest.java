package telegram.lernen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class TelegramClientTest {

    private static final String TOKEN = "123456:GEHEIM";

    @Test
    void liefertDenInhaltVonResult() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            JsonNode bot = new TelegramClient(TOKEN, fake.baseUrl()).aufrufen("getMe");
            assertEquals("fake_bot", bot.get("username").asText());
        }
    }

    @Test
    void sendetJsonAnDenPfadMitToken() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            new TelegramClient(TOKEN, fake.baseUrl()).aufrufen("sendMessage", TelegramClient.parameter().put("chat_id", 5).put("text", "Hi"));

            RecordedRequest aufruf = fake.requests("sendMessage").get(0);
            assertEquals(TOKEN, aufruf.token());
            assertTrue(aufruf.bodyContains("\"chat_id\":5"));
            assertTrue(aufruf.bodyContains("\"text\":\"Hi\""));
        }
    }

    @Test
    void okFalseWirdZurTelegramException() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.failNext("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

            TelegramException fehler = assertThrows(TelegramException.class,
                    () -> new TelegramClient(TOKEN, fake.baseUrl()).aufrufen("getMe"));
            assertEquals(401, fehler.errorCode());
            assertEquals(0, fehler.retryAfter());
            assertTrue(fehler.getMessage().contains("Unauthorized"));
            assertFalse(fehler.getMessage().contains("GEHEIM"));
        }
    }

    @Test
    void unbekannteMethodeIstEinFehler() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            TelegramException fehler = assertThrows(TelegramException.class,
                    () -> new TelegramClient(TOKEN, fake.baseUrl()).aufrufen("gibtEsNicht"));
            assertEquals(404, fehler.errorCode());
        }
    }

    @Test
    void rateLimitLiefertRetryAfter() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.rateLimitNext("getMe", 7);

            TelegramException fehler = assertThrows(TelegramException.class,
                    () -> new TelegramClient(TOKEN, fake.baseUrl()).aufrufen("getMe"));
            assertEquals(429, fehler.errorCode());
            assertEquals(7, fehler.retryAfter());
        }
    }
}
