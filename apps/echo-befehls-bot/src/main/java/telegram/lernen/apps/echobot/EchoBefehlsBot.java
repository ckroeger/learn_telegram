package telegram.lernen.apps.echobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/** Echo- und Befehls-Bot: spiegelt Text, kennt Befehle, eine Antwort-Tastatur und ein Menü mit Inline-Tasten. */
public class EchoBefehlsBot {

    record BefehlInfo(String name, String beschreibung) {
    }

    /** Die Befehle, die der Bot kennt und per setMyCommands anmeldet. */
    static final List<BefehlInfo> BEFEHLE = List.of(
            new BefehlInfo("start", "Bot starten"),
            new BefehlInfo("hilfe", "Alle Befehle anzeigen"),
            new BefehlInfo("echo", "Text zurückschicken, z. B. /echo Hallo"),
            new BefehlInfo("menue", "Menü mit Tasten anzeigen"),
            new BefehlInfo("tastatur", "Antwort-Tastatur einblenden"),
            new BefehlInfo("aus", "Antwort-Tastatur ausblenden"));

    private final TelegramClient client;
    private final String eigenerName;
    private final Map<Long, Integer> zaehler = new ConcurrentHashMap<>();

    public EchoBefehlsBot(TelegramClient client, String eigenerName) {
        this.client = client;
        this.eigenerName = eigenerName;
    }

    // #region start
    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        EchoBefehlsBot bot = new EchoBefehlsBot(client, eigenerName);

        bot.befehleRegistrieren();
        System.out.println("@" + eigenerName + " läuft, Strg+C beendet");
        new UpdatePoller(client, List.of("message", "callback_query")).laufen(bot.alsHandler());
    }
    // #endregion start

    // #region registrieren
    /** Meldet die Befehle bei Telegram an, damit der Client sie nach einem "/" vorschlägt. */
    void befehleRegistrieren() throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter();
        ArrayNode liste = parameter.putArray("commands");
        for (BefehlInfo befehl : BEFEHLE) {
            liste.addObject().put("command", befehl.name()).put("description", befehl.beschreibung());
        }
        client.aufrufen("setMyCommands", parameter);
    }
    // #endregion registrieren

    /** Für den {@link UpdatePoller}: prüft keine Ausnahmen, ein Fehler wird dort gemeldet. */
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
            callback(update.get("callback_query"));
        }
    }

    // #region verteiler
    private void nachricht(JsonNode nachricht) throws IOException, InterruptedException {
        long chatId = nachricht.path("chat").path("id").asLong();
        Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
        if (befehl.isPresent()) {
            befehlAusfuehren(chatId, befehl.get());
        } else if (Befehl.beginntMitBefehl(nachricht)) {
            // Befehl an einen anderen Bot (z. B. /hilfe@anderer_bot in einer Gruppe): nicht antworten.
        } else if (nachricht.has("text")) {
            senden(chatId, nachricht.get("text").asText());
        }
    }

    private void befehlAusfuehren(long chatId, Befehl befehl) throws IOException, InterruptedException {
        switch (befehl.name()) {
            case "start" -> senden(chatId, begruessung(befehl.argumente()));
            // /help ist einer der globalen Befehle, um die Telegram bittet. Registriert ist nur /hilfe.
            case "hilfe", "help" -> senden(chatId, hilfetext());
            case "echo" -> senden(chatId, befehl.argumente().isEmpty() ? "Benutzung: /echo <Text>" : befehl.argumente());
            case "menue" -> menueSenden(chatId);
            case "tastatur" -> tastaturSenden(chatId);
            case "aus" -> tastaturEntfernen(chatId);
            default -> senden(chatId, "Diesen Befehl kenne ich nicht: /" + befehl.name() + ". /hilfe zeigt alle Befehle.");
        }
    }
    // #endregion verteiler

    /** Bei einem Deep Link wie https://t.me/mein_bot?start=airplane kommt "/start airplane" an. */
    static String begruessung(String parameter) {
        String text = "Hallo! Ich spiegele deinen Text. /hilfe zeigt, was ich sonst kann.";
        return parameter.isEmpty() ? text : text + "\nDu hast mich über einen Link mit dem Parameter \"" + parameter + "\" geöffnet.";
    }

    static String hilfetext() {
        return BEFEHLE.stream().map(b -> "/" + b.name() + " - " + b.beschreibung()).collect(Collectors.joining("\n"));
    }

    // #region menue
    private void menueSenden(long chatId) throws IOException, InterruptedException {
        ObjectNode parameter = nachrichtParameter(chatId, "Was möchtest du tun?");
        parameter.set("reply_markup", Tastaturen.inline(List.of(
                List.of(Taste.daten("Zählen", "zaehlen"), Taste.daten("Zurücksetzen", "reset")),
                List.of(Taste.daten("Hilfe", "hilfe")),
                List.of(Taste.link("Doku öffnen", "https://core.telegram.org/bots/api")))));
        client.aufrufen("sendMessage", parameter);
    }
    // #endregion menue

    // #region callbacks
    private void callback(JsonNode abfrage) throws IOException, InterruptedException {
        long nutzer = abfrage.path("from").path("id").asLong();
        ObjectNode antwort = TelegramClient.parameter().put("callback_query_id", abfrage.path("id").asText());
        switch (abfrage.path("data").asText()) {
            case "zaehlen" -> antwort.put("text", "Zähler: " + zaehler.merge(nutzer, 1, Integer::sum));
            case "reset" -> {
                zaehler.remove(nutzer);
                antwort.put("text", "Zähler zurückgesetzt");
            }
            case "hilfe" -> antwort.put("text", kurzhilfe()).put("show_alert", true);
            default -> antwort.put("text", "Diese Taste ist veraltet.");
        }
        // Immer beantworten, sonst zeigt der Client weiter einen Ladebalken an der Taste.
        client.aufrufen("answerCallbackQuery", antwort);
    }
    // #endregion callbacks

    /** Höchstens 200 Zeichen, so viel erlaubt answerCallbackQuery. */
    static String kurzhilfe() {
        return "Befehle: " + BEFEHLE.stream().map(b -> "/" + b.name()).collect(Collectors.joining(" "));
    }

    private void tastaturSenden(long chatId) throws IOException, InterruptedException {
        ObjectNode parameter = nachrichtParameter(chatId, "Die Tastatur ist da. /aus blendet sie aus.");
        parameter.set("reply_markup", Tastaturen.antwort(List.of(List.of("/hilfe", "/menue"), List.of("/aus"))));
        client.aufrufen("sendMessage", parameter);
    }

    private void tastaturEntfernen(long chatId) throws IOException, InterruptedException {
        ObjectNode parameter = nachrichtParameter(chatId, "Tastatur ausgeblendet.");
        parameter.set("reply_markup", Tastaturen.entfernen());
        client.aufrufen("sendMessage", parameter);
    }

    private void senden(long chatId, String text) throws IOException, InterruptedException {
        client.aufrufen("sendMessage", nachrichtParameter(chatId, text));
    }

    private static ObjectNode nachrichtParameter(long chatId, String text) {
        return TelegramClient.parameter().put("chat_id", chatId).put("text", text);
    }
}
