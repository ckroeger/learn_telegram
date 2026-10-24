package telegram.lernen.bearbeiten;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class UebungLoesungTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode klick(String daten, String text) throws Exception {
        return JSON.readTree("{\"id\":\"c1\",\"data\":\"" + daten + "\",\"message\":{\"message_id\":55,"
                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"" + text + "\"}}");
    }

    @Test
    void zaehlerStartetBeiNullMitZweiTasten() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            UebungLoesung.zaehlerSenden(new TelegramClient("1:x", fake.baseUrl()), 42);

            JsonNode gesendet = JSON.readTree(fake.requests("sendMessage").get(0).body());
            assertEquals("Zähler: 0", gesendet.get("text").asText());
            assertEquals(2, gesendet.path("reply_markup").path("inline_keyboard").get(0).size());
        }
    }

    @Test
    void plusLiestDenStandAusDemTextUndBearbeitetMitTasten() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("editMessageText", "true");
            fake.onMethod("answerCallbackQuery", "true");

            UebungLoesung.klickBehandeln(new TelegramClient("1:x", fake.baseUrl()), klick("plus", "Zähler: 41"));

            JsonNode bearbeitet = JSON.readTree(fake.requests("editMessageText").get(0).body());
            assertEquals(55, bearbeitet.get("message_id").asLong());
            assertEquals("Zähler: 42", bearbeitet.get("text").asText());
            assertTrue(bearbeitet.has("reply_markup"), "die Tasten müssen erhalten bleiben");
            assertTrue(fake.requests("answerCallbackQuery").get(0).bodyContains("Jetzt 42"));
        }
    }

    @Test
    void loeschenEntferntDieNachricht() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("deleteMessage", "true");
            fake.onMethod("answerCallbackQuery", "true");

            UebungLoesung.klickBehandeln(new TelegramClient("1:x", fake.baseUrl()), klick("loeschen", "Zähler: 3"));

            assertTrue(fake.requests("deleteMessage").get(0).bodyContains("\"message_id\":55"));
            assertEquals(0, fake.requests("editMessageText").size());
        }
    }

    @Test
    void unbekannteTastenWerdenNurBeantwortet() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("answerCallbackQuery", "true");

            UebungLoesung.klickBehandeln(new TelegramClient("1:x", fake.baseUrl()), klick("alt", "Zähler: 3"));

            assertTrue(fake.requests("answerCallbackQuery").get(0).bodyContains("veraltet"));
            assertEquals(0, fake.requests("deleteMessage").size());
        }
    }
}
