package telegram.lernen.interaktion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/** Lösung der Übung: eine Ja/Nein-Frage mit Inline-Tasten, jeder Klick wird mit einem Hinweis beantwortet. */
public class UebungLoesung {

    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        frageSenden(client, Long.parseLong(args[0]));
        System.out.println("Frage gesendet, klicke in Telegram auf eine Taste (Strg+C beendet)");
        new UpdatePoller(client, List.of("callback_query")).laufen(update -> {
            try {
                klickBeantworten(client, update.path("callback_query"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // #region loesung
    static void frageSenden(TelegramClient client, long chatId) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("chat_id", chatId).put("text", "Gefällt dir die Lektion?");
        parameter.set("reply_markup", Tastaturen.inline(List.of(
                List.of(Taste.daten("Ja", "antwort:ja"), Taste.daten("Nein", "antwort:nein")))));
        client.aufrufen("sendMessage", parameter);
    }

    static void klickBeantworten(TelegramClient client, JsonNode abfrage) throws IOException, InterruptedException {
        String text = switch (abfrage.path("data").asText()) {
            case "antwort:ja" -> "Du hast Ja gewählt";
            case "antwort:nein" -> "Du hast Nein gewählt";
            default -> "Diese Taste ist veraltet.";
        };
        client.aufrufen("answerCallbackQuery",
                TelegramClient.parameter().put("callback_query_id", abfrage.path("id").asText()).put("text", text));
    }
    // #endregion loesung
}
