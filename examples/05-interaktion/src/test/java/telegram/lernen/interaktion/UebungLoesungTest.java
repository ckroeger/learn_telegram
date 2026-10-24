package telegram.lernen.interaktion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class UebungLoesungTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frageHatZweiTastenMitCallbackData() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            UebungLoesung.frageSenden(new TelegramClient("1:x", fake.baseUrl()), 42);

            JsonNode gesendet = JSON.readTree(fake.requests("sendMessage").get(0).body());
            JsonNode zeile = gesendet.path("reply_markup").path("inline_keyboard").get(0);
            assertEquals(2, zeile.size());
            assertEquals("antwort:ja", zeile.get(0).get("callback_data").asText());
            assertEquals("antwort:nein", zeile.get(1).get("callback_data").asText());
        }
    }

    @Test
    void jederKlickWirdMitDemRichtigenTextBeantwortet() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("answerCallbackQuery", "true");
            TelegramClient client = new TelegramClient("1:x", fake.baseUrl());

            UebungLoesung.klickBeantworten(client, JSON.readTree("{\"id\":\"a1\",\"data\":\"antwort:ja\"}"));
            UebungLoesung.klickBeantworten(client, JSON.readTree("{\"id\":\"a2\",\"data\":\"antwort:nein\"}"));
            UebungLoesung.klickBeantworten(client, JSON.readTree("{\"id\":\"a3\",\"data\":\"alt\"}"));

            var antworten = fake.requests("answerCallbackQuery");
            assertTrue(antworten.get(0).bodyContains("\"callback_query_id\":\"a1\""));
            assertTrue(antworten.get(0).bodyContains("Du hast Ja gewählt"));
            assertTrue(antworten.get(1).bodyContains("Du hast Nein gewählt"));
            assertTrue(antworten.get(2).bodyContains("veraltet"));
        }
    }
}
