package telegram.lernen.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import telegram.lernen.client.TelegramClient;

/**
 * Echo-Bot per Webhook: startet den Server, meldet ihn bei Telegram an und spiegelt Textnachrichten.
 *
 * <p>Konfiguration über Umgebungsvariablen: {@code WEBHOOK_URL} (öffentliche HTTPS-Adresse samt Pfad, Pflicht),
 * {@code PORT} (Standard 8080) und {@code WEBHOOK_SECRET} (Standard: ein zufälliges Geheimnis pro Start).
 */
public class WebhookBot {

    // #region start
    public static void main(String[] args) throws Exception {
        String url = System.getenv("WEBHOOK_URL");
        if (url == null || !url.startsWith("https://")) {
            System.err.println("WEBHOOK_URL fehlt oder ist nicht https://, z. B. https://beispiel.example/telegram");
            System.exit(2);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String secret = System.getenv().getOrDefault("WEBHOOK_SECRET", Webhook.neuesGeheimnis());
        TelegramClient client = TelegramClient.ausUmgebung();

        // Erst den Server starten, dann Telegram Bescheid geben: Sonst kommen Updates, bevor jemand zuhört.
        String pfad = URI.create(url).getPath().isEmpty() ? "/" : URI.create(url).getPath();
        WebhookServer server = WebhookServer.starten(port, pfad, secret, update -> antworten(client, update));
        Webhook.setzen(client, url, secret, List.of("message"), false);
        System.out.println("Webhook läuft auf Port " + port + ", Pfad " + pfad + ". Strg+C beendet.");
        Thread.currentThread().join();
        server.close();
    }
    // #endregion start

    // #region antworten
    /** Wird für jedes Update aufgerufen: spiegelt den Text einer Nachricht. */
    static void antworten(TelegramClient client, JsonNode update) {
        JsonNode nachricht = update.path("message");
        if (!nachricht.has("text")) {
            return;
        }
        try {
            client.aufrufen("sendMessage", TelegramClient.parameter()
                    .put("chat_id", nachricht.path("chat").path("id").asLong())
                    .put("text", nachricht.get("text").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    // #endregion antworten
}
