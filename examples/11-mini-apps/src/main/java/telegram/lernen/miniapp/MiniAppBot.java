package telegram.lernen.miniapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import telegram.lernen.client.Befehl;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;

/**
 * Der Bot zur Mini App: setzt die Menütaste, öffnet die Mini App über eine Inline-Taste oder eine Antwort-Taste
 * und nimmt Daten entgegen, die die Mini App mit {@code sendData} schickt.
 */
public class MiniAppBot {

    /** Mehr zeigt der Bot von den empfangenen Daten nicht an. */
    static final int MAX_ANZEIGE = 1000;

    private final TelegramClient client;
    private final String eigenerName;
    private final String miniAppUrl;

    public MiniAppBot(TelegramClient client, String eigenerName, String miniAppUrl) {
        if (!miniAppUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Eine Mini App braucht eine HTTPS-Adresse, bekommen: " + miniAppUrl);
        }
        this.client = client;
        this.eigenerName = eigenerName;
        this.miniAppUrl = miniAppUrl;
    }

    // #region menue
    /** Ersetzt die Menütaste neben dem Eingabefeld (für alle Nutzer) durch eine, die die Mini App öffnet. */
    void menueSetzen() throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter();
        ObjectNode menue = parameter.putObject("menu_button");
        menue.put("type", "web_app").put("text", "Mini App");
        menue.putObject("web_app").put("url", miniAppUrl);
        client.aufrufen("setChatMenuButton", parameter);
    }
    // #endregion menue

    public Consumer<JsonNode> alsHandler() {
        return update -> {
            try {
                behandle(update);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    public void behandle(JsonNode update) throws IOException, InterruptedException {
        JsonNode nachricht = update.path("message");
        if (nachricht.isMissingNode()) {
            return;
        }
        long chatId = nachricht.path("chat").path("id").asLong();
        // #region webappdata
        if (nachricht.has("web_app_data")) {
            // Ein manipulierter Client kann hier beliebigen Text einschleusen: nur anzeigen, nichts davon ausführen.
            String daten = nachricht.path("web_app_data").path("data").asText();
            senden(chatId, "Die Mini App hat gesendet: " + kuerzen(daten), null);
            return;
        }
        // #endregion webappdata
        Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
        if (befehl.isEmpty()) {
            return;
        }
        switch (befehl.get().name()) {
            case "formular" -> senden(chatId, "Die Taste unten öffnet ein Formular. Es schickt seine Daten mit sendData an mich.",
                    Tastaturen.antwortMitWebApp("Formular öffnen", miniAppUrl));
            case "aus" -> senden(chatId, "Tastatur ausgeblendet.", Tastaturen.entfernen());
            default -> senden(chatId, "Die Taste öffnet die Mini App, die Daten per Hash prüft und eine Nachricht für dich sendet. "
                    + "Dasselbe geht über die Menütaste. /formular zeigt den Weg über eine Antwort-Taste.",
                    Tastaturen.inline(List.of(List.of(Taste.webApp("Mini App öffnen", miniAppUrl)))));
        }
    }

    static String kuerzen(String text) {
        return text.length() <= MAX_ANZEIGE ? text : text.substring(0, MAX_ANZEIGE) + "…";
    }

    private void senden(long chatId, String text, ObjectNode markup) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("chat_id", chatId).put("text", text);
        if (markup != null) {
            parameter.set("reply_markup", markup);
        }
        client.aufrufen("sendMessage", parameter);
    }
}
