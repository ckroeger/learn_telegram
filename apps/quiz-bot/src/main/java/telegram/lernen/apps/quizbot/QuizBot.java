package telegram.lernen.apps.quizbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import telegram.lernen.client.Befehl;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/**
 * Quiz-Bot: stellt Quizfragen als Umfrage ({@code sendPoll} im Quiz-Modus), wertet {@code poll_answer}-Updates aus,
 * zählt Punkte pro Chat und Nutzer und zeigt eine Bestenliste. Alles liegt im Arbeitsspeicher.
 */
public class QuizBot {

    /** Eine gesendete Frage: in welchem Chat sie steht und welche Frage das ist. */
    record Gestellt(long chatId, int frage) {
    }

    /** Punktestand eines Nutzers in einem Chat. */
    static final class Spieler {
        final String name;
        int richtig;
        int antworten;

        Spieler(String name) {
            this.name = name;
        }
    }

    private static final int BESTENLISTE = 10;

    private final TelegramClient client;
    private final String eigenerName;
    private final List<Frage> fragen;

    /** poll_id -> Frage. Ein poll_answer enthält keine Chat-ID, nur die ID der Umfrage. */
    private final Map<String, Gestellt> gestellt = new ConcurrentHashMap<>();
    /** Chat -> Nummer der zuletzt gestellten Frage. Daran erkennt "Nächste Frage" einen doppelten Klick. */
    private final Map<Long, Integer> letzteFrage = new ConcurrentHashMap<>();
    /** Chat -> Nutzer -> Punktestand. */
    private final Map<Long, Map<Long, Spieler>> punkte = new ConcurrentHashMap<>();
    /** "pollId:nutzerId", damit jede Antwort nur einmal zählt. */
    private final Set<String> gewertet = ConcurrentHashMap.newKeySet();

    public QuizBot(TelegramClient client, String eigenerName, List<Frage> fragen) {
        if (fragen.isEmpty()) {
            throw new IllegalArgumentException("Ein Quiz braucht mindestens eine Frage");
        }
        this.client = client;
        this.eigenerName = eigenerName;
        this.fragen = List.copyOf(fragen);
    }

    // #region start
    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        QuizBot bot = new QuizBot(client, eigenerName, Fragen.STANDARD);

        System.out.println("@" + eigenerName + " läuft, Strg+C beendet");
        // poll_answer kommt nur, wenn man es in allowed_updates anfordert.
        new UpdatePoller(client, List.of("message", "callback_query", "poll_answer")).laufen(bot.alsHandler());
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
        if (update.has("message")) {
            nachricht(update.get("message"));
        } else if (update.has("callback_query")) {
            klick(update.get("callback_query"));
        } else if (update.has("poll_answer")) {
            antwort(update.get("poll_answer"));
        }
    }

    private void nachricht(JsonNode nachricht) throws IOException, InterruptedException {
        long chatId = nachricht.path("chat").path("id").asLong();
        Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
        if (befehl.isEmpty()) {
            return;
        }
        switch (befehl.get().name()) {
            case "start", "hilfe", "help" -> senden(chatId, hilfetext());
            case "quiz" -> frageStellen(chatId, 0);
            case "rangliste" -> senden(chatId, bestenliste(chatId));
            default -> senden(chatId, "Diesen Befehl kenne ich nicht. /hilfe zeigt alle Befehle.");
        }
    }

    static String hilfetext() {
        return "Ich stelle Quizfragen.\n/quiz - neue Runde starten\n/rangliste - Bestenliste dieses Chats\n"
                + "Antworte direkt in der Umfrage, mit der Taste darunter kommt die nächste Frage.";
    }

    // #region frage
    /** Sendet eine Frage als Quiz-Umfrage mit einer Taste für die nächste Frage (oder das Ergebnis). */
    void frageStellen(long chatId, int nummer) throws IOException, InterruptedException {
        Frage frage = fragen.get(nummer);
        ObjectNode parameter = TelegramClient.parameter()
                .put("chat_id", chatId)
                .put("question", "(" + (nummer + 1) + "/" + fragen.size() + ") " + frage.text())
                .put("type", "quiz")
                // Nur in nicht anonymen Umfragen erfährt der Bot, wer wie geantwortet hat.
                .put("is_anonymous", false)
                .put("explanation", frage.erklaerung());
        parameter.putArray("correct_option_ids").add(frage.richtig());
        for (String option : frage.optionen()) {
            parameter.withArray("options").addObject().put("text", option);
        }
        boolean letzte = nummer == fragen.size() - 1;
        Taste taste = letzte ? Taste.daten("Ergebnis anzeigen", "ergebnis") : Taste.daten("Nächste Frage", "weiter:" + (nummer + 1));
        parameter.set("reply_markup", Tastaturen.inline(List.of(List.of(taste))));

        JsonNode nachricht = client.aufrufen("sendPoll", parameter);
        // Die Antwort enthält die Umfrage mit ihrer ID: darüber ordnen wir spätere Antworten zu.
        gestellt.put(nachricht.path("poll").path("id").asText(), new Gestellt(chatId, nummer));
        letzteFrage.put(chatId, nummer);
    }
    // #endregion frage

    // #region antwort
    /** Ein Nutzer hat in einer Umfrage des Bots geantwortet. */
    private void antwort(JsonNode abstimmung) {
        String pollId = abstimmung.path("poll_id").asText();
        Gestellt ziel = gestellt.get(pollId);
        JsonNode nutzer = abstimmung.path("user");
        JsonNode gewaehlt = abstimmung.path("option_ids");
        // Unbekannte Umfrage (z. B. von vor einem Neustart), anonymer Absender (nur voter_chat) oder zurückgezogene Stimme.
        if (ziel == null || !nutzer.has("id") || gewaehlt.isEmpty()) {
            return;
        }
        long nutzerId = nutzer.get("id").asLong();
        if (!gewertet.add(pollId + ":" + nutzerId)) {
            return;
        }
        boolean richtig = gewaehlt.size() == 1 && gewaehlt.get(0).asInt() == fragen.get(ziel.frage()).richtig();
        Spieler spieler = punkte.computeIfAbsent(ziel.chatId(), c -> new ConcurrentHashMap<>())
                .computeIfAbsent(nutzerId, id -> new Spieler(anzeigename(nutzer)));
        synchronized (spieler) {
            spieler.antworten++;
            if (richtig) {
                spieler.richtig++;
            }
        }
    }
    // #endregion antwort

    private static String anzeigename(JsonNode nutzer) {
        String name = nutzer.path("first_name").asText("");
        if (name.isBlank()) {
            name = nutzer.path("username").asText("Unbekannt");
        }
        return name;
    }

    // #region klick
    private void klick(JsonNode abfrage) throws IOException, InterruptedException {
        ObjectNode antwort = TelegramClient.parameter().put("callback_query_id", abfrage.path("id").asText());
        JsonNode chat = abfrage.path("message").path("chat");
        String daten = abfrage.path("data").asText();
        String hinweis = null;
        if (!chat.has("id")) {
            hinweis = "Diese Taste ist veraltet.";
        } else if (daten.equals("ergebnis")) {
            senden(chat.get("id").asLong(), bestenliste(chat.get("id").asLong()));
        } else if (daten.startsWith("weiter:")) {
            hinweis = weiter(chat.get("id").asLong(), daten.substring("weiter:".length()));
        } else {
            hinweis = "Diese Taste ist veraltet.";
        }
        if (hinweis != null) {
            antwort.put("text", hinweis);
        }
        // Immer beantworten, sonst zeigt der Client weiter einen Ladebalken an der Taste.
        client.aufrufen("answerCallbackQuery", antwort);
    }

    /** Stellt die nächste Frage, aber nur einmal: der Klick muss zur zuletzt gestellten Frage passen. */
    private String weiter(long chatId, String nummerText) throws IOException, InterruptedException {
        int nummer;
        try {
            nummer = Integer.parseInt(nummerText);
        } catch (NumberFormatException e) {
            return "Diese Taste ist veraltet.";
        }
        Integer zuletzt = letzteFrage.get(chatId);
        if (nummer < 1 || nummer >= fragen.size() || zuletzt == null || zuletzt != nummer - 1) {
            return "Diese Frage wurde schon gestellt. /quiz startet eine neue Runde.";
        }
        frageStellen(chatId, nummer);
        return null;
    }
    // #endregion klick

    // #region bestenliste
    /** Die besten Spieler dieses Chats: erst nach richtigen Antworten, dann nach weniger Versuchen, dann nach Name. */
    String bestenliste(long chatId) {
        Map<Long, Spieler> spieler = punkte.getOrDefault(chatId, Map.of());
        if (spieler.isEmpty()) {
            return "Noch keine Antworten. /quiz startet eine Runde.";
        }
        List<Spieler> sortiert = new ArrayList<>(spieler.values());
        sortiert.sort(Comparator.<Spieler>comparingInt(s -> -s.richtig)
                .thenComparingInt(s -> s.antworten)
                .thenComparing(s -> s.name));
        StringBuilder text = new StringBuilder("Bestenliste");
        int platz = 1;
        for (Spieler s : sortiert.subList(0, Math.min(BESTENLISTE, sortiert.size()))) {
            text.append('\n').append(platz++).append(". ").append(s.name)
                    .append(": ").append(s.richtig).append(" von ").append(s.antworten).append(" richtig");
        }
        return text.toString();
    }
    // #endregion bestenliste

    private void senden(long chatId, String text) throws IOException, InterruptedException {
        client.aufrufen("sendMessage", TelegramClient.parameter().put("chat_id", chatId).put("text", text));
    }
}
