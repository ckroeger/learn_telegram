package telegram.lernen.apps.inlinesuche;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import telegram.lernen.client.Befehl;
import telegram.lernen.client.Inline;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.client.UpdatePoller;

/**
 * Inline-Such-Bot: Wer in einem beliebigen Chat {@code @bot Stichwort} tippt, sieht passende Methoden der Bot API und
 * kann eine davon mit Link in den Chat senden. Ohne Stichwort kommen die am häufigsten gewählten Methoden. Welche
 * Methode gewählt wurde, erfährt der Bot über {@code chosen_inline_result}.
 */
public class InlineSuchBot {

    /** So viele Ergebnisse je Antwort; mehr gibt es über next_offset auf der nächsten Seite. */
    static final int SEITE = 10;
    /** Telegram merkt sich eine Antwort so viele Sekunden und fragt den Bot in der Zeit nicht noch einmal. */
    static final int CACHE_SEKUNDEN = 30;

    private final TelegramClient client;
    private final String eigenerName;
    private final Katalog katalog;
    /** result_id -> wie oft gewählt. Nach einem Neustart wieder leer. */
    private final Map<String, Integer> gewaehlt = new ConcurrentHashMap<>();

    public InlineSuchBot(TelegramClient client, String eigenerName, Katalog katalog) {
        this.client = client;
        this.eigenerName = eigenerName;
        this.katalog = katalog;
    }

    // #region start
    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        InlineSuchBot bot = new InlineSuchBot(client, eigenerName, Katalog.ausResource());

        System.out.println("@" + eigenerName + " läuft, Strg+C beendet");
        new UpdatePoller(client, List.of("message", "inline_query", "chosen_inline_result")).laufen(bot.alsHandler());
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

    public void behandle(JsonNode update) throws IOException, InterruptedException {
        if (update.has("inline_query")) {
            abfrage(update.get("inline_query"));
        } else if (update.has("chosen_inline_result")) {
            auswahl(update.get("chosen_inline_result"));
        } else if (update.has("message")) {
            nachricht(update.get("message"));
        }
    }

    // #region abfrage
    private void abfrage(JsonNode abfrage) throws IOException, InterruptedException {
        String suchwort = abfrage.path("query").asText().strip();
        int start = seitenStart(abfrage.path("offset").asText());
        // Leere Abfrage: die beliebtesten Einträge. Sonst die Treffer, beste zuerst.
        List<Eintrag> treffer = suchwort.isEmpty() ? nachBeliebtheit() : katalog.suche(suchwort);
        List<Eintrag> seite = treffer.subList(Math.min(start, treffer.size()), Math.min(start + SEITE, treffer.size()));

        ObjectNode antwort = Inline.antwort(abfrage.get("id").asText(), seite.stream()
                .map(e -> Inline.artikel(e.name(), e.name(), e.beschreibung(), e.nachricht()))
                .toList());
        antwort.put("cache_time", CACHE_SEKUNDEN);
        // Es gibt weitere Treffer? Dann sagt next_offset dem Client, womit er die nächste Seite anfordert. Leer = Ende.
        antwort.put("next_offset", start + SEITE < treffer.size() ? String.valueOf(start + SEITE) : "");
        // Die Taste über den Ergebnissen öffnet den Chat mit dem Bot und sendet dort "/start <parameter>".
        antwort.putObject("button")
                .put("text", seite.isEmpty() ? "Nichts gefunden - Hilfe im Chat" : "Im Chat mit dem Bot suchen")
                .put("start_parameter", seite.isEmpty() ? "hilfe" : "suche");
        try {
            client.aufrufen("answerInlineQuery", antwort);
        } catch (TelegramException e) {
            // Der Nutzer tippt weiter, eine alte Abfrage kann abgelaufen sein. Das ist kein Grund, den Bot zu stoppen.
            System.err.println("Antwort auf die Inline-Abfrage abgelehnt: " + e.getMessage());
        }
    }

    /** Der Offset kommt vom Client. Alles, was keine Zahl ab 0 ist, zählt als Anfang. */
    static int seitenStart(String offset) {
        try {
            return Math.max(0, Integer.parseInt(offset));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Eintrag> nachBeliebtheit() {
        // sorted ist stabil: bei gleicher Anzahl bleibt die Reihenfolge des Katalogs.
        return katalog.alle().stream()
                .sorted(Comparator.comparingInt((Eintrag e) -> -gewaehlt.getOrDefault(e.name(), 0)))
                .toList();
    }
    // #endregion abfrage

    // #region auswahl
    /** Kommt nur an, wenn bei @BotFather mit /setinlinefeedback das Feedback eingeschaltet ist. */
    private void auswahl(JsonNode ergebnis) {
        String id = ergebnis.path("result_id").asText();
        int anzahl = gewaehlt.merge(id, 1, Integer::sum);
        System.out.println("Gewählt: " + id + " (" + anzahl + "x)");
    }
    // #endregion auswahl

    // #region chat
    private void nachricht(JsonNode nachricht) throws IOException, InterruptedException {
        Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
        if (befehl.isEmpty() && Befehl.beginntMitBefehl(nachricht)) {
            return; // Befehl an einen anderen Bot
        }
        long chatId = nachricht.path("chat").path("id").asLong();
        ObjectNode parameter = TelegramClient.parameter().put("chat_id", chatId)
                .put("text", antworttext(befehl.map(Befehl::name).orElse(""), befehl.map(Befehl::argumente).orElse("")));
        parameter.set("reply_markup", Tastaturen.inline(List.of(List.of(
                Taste.inlineHier("Hier suchen", ""), Taste.inlineInAnderemChat("In einem anderen Chat suchen", "")))));
        client.aufrufen("sendMessage", parameter);
    }

    /** Bei der Taste "Im Chat mit dem Bot suchen" kommt "/start suche" an, bei "Nichts gefunden" "/start hilfe". */
    String antworttext(String befehl, String parameter) {
        String hilfe = "Ich suche Methoden der Bot API. Tippe in einem beliebigen Chat @" + eigenerName
                + " und ein Stichwort, zum Beispiel \"photo\". Ein Tipp auf ein Ergebnis sendet es in den Chat.";
        if (befehl.equals("start") && parameter.equals("hilfe")) {
            return "Dazu habe ich nichts gefunden. Versuche ein anderes Stichwort, den Namen oder einen Teil der "
                    + "Beschreibung.\n\n" + hilfe;
        }
        return hilfe;
    }
    // #endregion chat
}
