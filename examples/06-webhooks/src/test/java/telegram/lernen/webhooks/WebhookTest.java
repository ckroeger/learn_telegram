package telegram.lernen.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.testsupport.FakeTelegramServer;

class WebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void setzenSendetUrlGeheimnisUndImmerAllowedUpdates() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("setWebhook", "true");

            Webhook.setzen(new TelegramClient("1:x", fake.baseUrl()), "https://beispiel.example/hook", "geheim",
                    List.of("message", "callback_query"), false);

            JsonNode body = JSON.readTree(fake.requests("setWebhook").get(0).body());
            assertEquals("https://beispiel.example/hook", body.get("url").asText());
            assertEquals("geheim", body.get("secret_token").asText());
            assertEquals(List.of("message", "callback_query"), List.of(body.get("allowed_updates").get(0).asText(),
                    body.get("allowed_updates").get(1).asText()));
            assertFalse(body.has("drop_pending_updates"));
        }
    }

    @Test
    void eineLeereListeSendetTrotzdemAllowedUpdatesUndVerwerfenWirdNurBeiTrueGesendet() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("setWebhook", "true");
            TelegramClient client = new TelegramClient("1:x", fake.baseUrl());

            Webhook.setzen(client, "https://beispiel.example/hook", "g", List.of(), true);

            JsonNode body = JSON.readTree(fake.requests("setWebhook").get(0).body());
            assertTrue(body.get("allowed_updates").isArray());
            assertEquals(0, body.get("allowed_updates").size());
            assertTrue(body.get("drop_pending_updates").asBoolean());
        }
    }

    @Test
    void infoLiefertDenStatus() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("getWebhookInfo", "{\"url\":\"https://beispiel.example/hook\",\"has_custom_certificate\":false,"
                    + "\"pending_update_count\":3,\"last_error_message\":\"Connection refused\"}");

            JsonNode info = Webhook.info(new TelegramClient("1:x", fake.baseUrl()));

            assertEquals("https://beispiel.example/hook", info.get("url").asText());
            assertEquals(3, info.get("pending_update_count").asInt());
            assertEquals("Connection refused", info.get("last_error_message").asText());
        }
    }

    @Test
    void loeschenRuftDeleteWebhookAuf() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("deleteWebhook", "true");
            TelegramClient client = new TelegramClient("1:x", fake.baseUrl());

            Webhook.loeschen(client, false);
            Webhook.loeschen(client, true);

            assertFalse(fake.requests("deleteWebhook").get(0).bodyContains("drop_pending_updates"));
            assertTrue(fake.requests("deleteWebhook").get(1).bodyContains("\"drop_pending_updates\":true"));
        }
    }

    @Test
    void telegramFehlerBeimSetzenWerdenWeitergegeben() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.failNext("setWebhook", 400,
                    "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: bad webhook: HTTPS url must be provided for webhook\"}");

            TelegramException fehler = org.junit.jupiter.api.Assertions.assertThrows(TelegramException.class,
                    () -> Webhook.setzen(new TelegramClient("1:x", fake.baseUrl()), "http://beispiel.example", "g",
                            List.of(), false));
            assertEquals(400, fehler.errorCode());
        }
    }

    @Test
    void dasGeheimnisHatDenErlaubtenZeichensatzUndIstJedesMalAnders() {
        Set<String> alle = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String geheimnis = Webhook.neuesGeheimnis();
            assertTrue(geheimnis.matches("[A-Za-z0-9_-]{43}"), geheimnis);
            alle.add(geheimnis);
        }
        assertEquals(100, alle.size());
    }

    @Test
    void ganzerWegVonTelegramBisZurAntwort() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            TelegramClient client = new TelegramClient("1:x", fake.baseUrl());
            String geheimnis = Webhook.neuesGeheimnis();
            try (WebhookServer server = WebhookServer.starten(0, "/hook", geheimnis,
                    update -> WebhookBot.antworten(client, update))) {
                // So würde Telegram das Update schicken.
                HttpRequest anfrage = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/hook"))
                        .header(WebhookServer.SECRET_HEADER, geheimnis)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"update_id\":1,\"message\":{\"message_id\":1,"
                                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"date\":1,\"text\":\"Hallo Webhook\"}}"))
                        .build();
                HttpResponse<String> antwort = HttpClient.newHttpClient().send(anfrage,
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, antwort.statusCode());
                var gesendet = fake.awaitRequests("sendMessage", 1, Duration.ofSeconds(5)).get(0);
                assertTrue(gesendet.bodyContains("\"chat_id\":42"));
                assertTrue(gesendet.bodyContains("\"text\":\"Hallo Webhook\""));
            }
        }
    }

    @Test
    void updatesOhneTextBleibenUnbeantwortet() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            WebhookBot.antworten(new TelegramClient("1:x", fake.baseUrl()),
                    JSON.readTree("{\"update_id\":2,\"message\":{\"message_id\":2,\"chat\":{\"id\":42},"
                            + "\"sticker\":{\"file_id\":\"x\"}}}"));
            WebhookBot.antworten(new TelegramClient("1:x", fake.baseUrl()),
                    JSON.readTree("{\"update_id\":3,\"callback_query\":{\"id\":\"1\"}}"));

            assertEquals(0, fake.requests("sendMessage").size());
        }
    }
}
