package telegram.lernen.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import telegram.lernen.client.Inline;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/**
 * Der kleinste Inline-Bot: Wer in einem beliebigen Chat {@code @dein_bot Hallo} tippt, sieht ein Ergebnis "Senden: Hallo".
 * Ein Tipp darauf schickt den Text in den Chat. Voraussetzung: Inline-Modus bei @BotFather mit /setinline aktiviert.
 */
public class InlineEcho {

    private final TelegramClient client;

    public InlineEcho(TelegramClient client) {
        this.client = client;
    }

    // #region start
    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        System.out.println("Inline-Echo läuft, Strg+C beendet");
        // Ohne "inline_query" in allowed_updates bekommt der Bot keine Abfragen.
        new UpdatePoller(client, List.of("inline_query")).laufen(new InlineEcho(client).alsHandler());
    }
    // #endregion start

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

    // #region antworten
    void behandle(JsonNode update) throws IOException, InterruptedException {
        JsonNode abfrage = update.path("inline_query");
        if (abfrage.isMissingNode()) {
            return;
        }
        String text = abfrage.path("query").asText().strip();
        // Bei leerer Abfrage gibt es nichts zu senden: eine leere Liste ist eine gültige Antwort.
        List<ObjectNode> ergebnisse = text.isEmpty()
                ? List.of()
                : List.of(Inline.artikel("echo", "Senden: " + text, "Schickt deinen Text in den Chat", text));
        ObjectNode antwort = Inline.antwort(abfrage.get("id").asText(), ergebnisse);
        // Telegram merkt sich Antworten standardmäßig 300 Sekunden. Zum Ausprobieren ist eine kurze Zeit bequemer.
        antwort.put("cache_time", 5);
        client.aufrufen("answerInlineQuery", antwort);
    }
    // #endregion antworten
}
