package telegram.lernen.bearbeiten;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/**
 * Lösung der Übung: Ein Zähler, dessen Stand in der Nachricht selbst steht. "+1" bearbeitet die Nachricht,
 * "Löschen" entfernt sie. Der Bot merkt sich nichts und überlebt deshalb einen Neustart.
 */
public class UebungLoesung {

    private static final String PRAEFIX = "Zähler: ";

    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        zaehlerSenden(client, Long.parseLong(args[0]));
        System.out.println("Zähler gesendet, klicke in Telegram auf +1 (Strg+C beendet)");
        new UpdatePoller(client, List.of("callback_query")).laufen(update -> {
            try {
                klickBehandeln(client, update.path("callback_query"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // #region loesung
    private static ObjectNode tasten() {
        return Tastaturen.inline(List.of(List.of(Taste.daten("+1", "plus"), Taste.daten("Löschen", "loeschen"))));
    }

    static void zaehlerSenden(TelegramClient client, long chatId) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("chat_id", chatId).put("text", PRAEFIX + 0);
        parameter.set("reply_markup", tasten());
        client.aufrufen("sendMessage", parameter);
    }

    static void klickBehandeln(TelegramClient client, JsonNode abfrage) throws IOException, InterruptedException {
        JsonNode nachricht = abfrage.path("message");
        long chatId = nachricht.path("chat").path("id").asLong();
        long nachrichtId = nachricht.path("message_id").asLong();
        String antwort = "Diese Taste ist veraltet.";

        switch (abfrage.path("data").asText()) {
            case "plus" -> {
                // Der Stand steht im Text der Nachricht, so braucht der Bot kein eigenes Gedächtnis.
                int stand = Integer.parseInt(nachricht.path("text").asText().substring(PRAEFIX.length())) + 1;
                ObjectNode parameter = TelegramClient.parameter()
                        .put("chat_id", chatId).put("message_id", nachrichtId).put("text", PRAEFIX + stand);
                parameter.set("reply_markup", tasten()); // ohne reply_markup wären die Tasten weg
                client.aufrufen("editMessageText", parameter);
                antwort = "Jetzt " + stand;
            }
            case "loeschen" -> {
                client.aufrufen("deleteMessage",
                        TelegramClient.parameter().put("chat_id", chatId).put("message_id", nachrichtId));
                antwort = "Gelöscht";
            }
            default -> { }
        }
        client.aufrufen("answerCallbackQuery",
                TelegramClient.parameter().put("callback_query_id", abfrage.path("id").asText()).put("text", antwort));
    }
    // #endregion loesung
}
