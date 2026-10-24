package telegram.lernen.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import telegram.lernen.client.TelegramClient;

/** Meldet den Webhook bei Telegram an, liest seinen Status und löscht ihn wieder. */
public final class Webhook {

    private static final SecureRandom ZUFALL = new SecureRandom();

    private Webhook() {
    }

    // #region setzen
    /**
     * Teilt Telegram mit, wohin es Updates schicken soll.
     *
     * @param url             öffentliche HTTPS-Adresse, Port 443, 80, 88 oder 8443
     * @param secret          kommt bei jeder Anfrage im Header {@code X-Telegram-Bot-Api-Secret-Token} zurück
     * @param allowedUpdates  gewünschte Update-Typen, leer heißt "Standard"
     * @param verwerfen       {@code true} verwirft Updates, die sich bis jetzt angesammelt haben
     */
    public static void setzen(TelegramClient client, String url, String secret, List<String> allowedUpdates,
            boolean verwerfen) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("url", url).put("secret_token", secret);
        // Immer mitsenden: Fehlt das Feld, gilt die vorherige Einstellung weiter (wie bei getUpdates).
        ArrayNode liste = parameter.putArray("allowed_updates");
        allowedUpdates.forEach(liste::add);
        if (verwerfen) {
            parameter.put("drop_pending_updates", true);
        }
        client.aufrufen("setWebhook", parameter);
    }
    // #endregion setzen

    // #region info
    /** Der Status laut Telegram: {@code url}, {@code pending_update_count}, {@code last_error_message} u. a. */
    public static JsonNode info(TelegramClient client) throws IOException, InterruptedException {
        return client.aufrufen("getWebhookInfo");
    }

    /** Entfernt den Webhook. Erst danach funktioniert {@code getUpdates} wieder. */
    public static void loeschen(TelegramClient client, boolean verwerfen) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter();
        if (verwerfen) {
            parameter.put("drop_pending_updates", true);
        }
        client.aufrufen("deleteWebhook", parameter);
    }
    // #endregion info

    // #region geheimnis
    /** 32 zufällige Bytes als 43 Zeichen aus A-Z, a-z, 0-9, _ und -, genau das, was Telegram erlaubt. */
    public static String neuesGeheimnis() {
        byte[] zufall = new byte[32];
        ZUFALL.nextBytes(zufall);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(zufall);
    }
    // #endregion geheimnis
}
