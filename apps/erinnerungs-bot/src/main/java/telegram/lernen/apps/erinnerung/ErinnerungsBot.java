package telegram.lernen.apps.erinnerung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import telegram.lernen.client.Befehl;
import telegram.lernen.client.Tastaturen;
import telegram.lernen.client.Tastaturen.Taste;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.client.UpdatePoller;

/**
 * Erinnerungs-Bot: {@code /erinnere 10m Tee} zeigt einen Status mit "Abbrechen"-Taste, löscht ihn zur Fälligkeit und
 * schickt dann die Erinnerung, die sich mit "Erledigt" oder "Später" bearbeiten lässt.
 */
public class ErinnerungsBot {

    static final int MAX_OFFEN = 20;
    static final Duration SPAETER = Duration.ofMinutes(5);

    private final TelegramClient client;
    private final String eigenerName;
    private final Planer planer;
    private final Clock uhr;
    private final AtomicLong naechsteId = new AtomicLong(1);
    /** Erinnerungen, die noch laufen, und solche, die ausgelöst wurden und auf "Erledigt" oder "Später" warten. */
    private final Map<Long, Erinnerung> offen = new ConcurrentHashMap<>();
    private final Map<Long, Erinnerung> ausgeloest = new ConcurrentHashMap<>();

    ErinnerungsBot(TelegramClient client, String eigenerName, Planer planer, Clock uhr) {
        this.client = client;
        this.eigenerName = eigenerName;
        this.planer = planer;
        this.uhr = uhr;
    }

    // #region start
    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        ErinnerungsBot bot = new ErinnerungsBot(client, eigenerName, Planer.echt(), Clock.systemUTC());

        bot.befehleRegistrieren();
        System.out.println("@" + eigenerName + " läuft, Strg+C beendet. Erinnerungen gehen beim Beenden verloren.");
        new UpdatePoller(client, List.of("message", "callback_query")).laufen(bot.alsHandler());
    }
    // #endregion start

    void befehleRegistrieren() throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter();
        ArrayNode liste = parameter.putArray("commands");
        liste.addObject().put("command", "erinnere").put("description", "Erinnerung setzen: /erinnere 10m Tee");
        liste.addObject().put("command", "liste").put("description", "Offene Erinnerungen anzeigen");
        liste.addObject().put("command", "hilfe").put("description", "Hilfe anzeigen");
        client.aufrufen("setMyCommands", parameter);
    }

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

    void behandle(JsonNode update) throws IOException, InterruptedException {
        if (update.has("message")) {
            JsonNode nachricht = update.get("message");
            long chatId = nachricht.path("chat").path("id").asLong();
            Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
            if (befehl.isPresent()) {
                befehlAusfuehren(chatId, befehl.get());
            } else if (!Befehl.beginntMitBefehl(nachricht) && nachricht.has("text")) {
                senden(chatId, "Ich verstehe nur Befehle. /hilfe zeigt sie.");
            }
        } else if (update.has("callback_query")) {
            klick(update.get("callback_query"));
        }
    }

    private void befehlAusfuehren(long chatId, Befehl befehl) throws IOException, InterruptedException {
        switch (befehl.name()) {
            case "erinnere" -> erinnern(chatId, befehl.argumente());
            case "liste" -> liste(chatId);
            case "start", "hilfe", "help" -> senden(chatId, hilfetext());
            default -> senden(chatId, "Diesen Befehl kenne ich nicht. /hilfe zeigt alle Befehle.");
        }
    }

    static String hilfetext() {
        return """
                Ich erinnere dich an Dinge.
                /erinnere <Zeit> <Text> setzt eine Erinnerung, z. B. /erinnere 10m Tee ziehen lassen
                Die Zeit ist eine Zahl mit s, m, h oder d (Sekunden, Minuten, Stunden, Tage), höchstens 7d.
                /liste zeigt offene Erinnerungen.""";
    }

    // #region erinnere
    private void erinnern(long chatId, String argumente) throws IOException, InterruptedException {
        String[] teile = argumente.split("\\s+", 2);
        Optional<Duration> dauer = teile[0].isEmpty() ? Optional.empty() : Dauer.lesen(teile[0]);
        if (dauer.isEmpty() || teile.length < 2 || teile[1].isBlank()) {
            senden(chatId, "Benutzung: /erinnere <Zeit> <Text>, z. B. /erinnere 10m Tee ziehen lassen\n"
                    + "Zeit: Zahl mit s, m, h oder d, höchstens 7d.");
            return;
        }
        if (offen.values().stream().filter(e -> e.chatId == chatId).count() >= MAX_OFFEN) {
            senden(chatId, "Du hast schon " + MAX_OFFEN + " offene Erinnerungen. Warte, bis eine fällig ist.");
            return;
        }
        long id = naechsteId.getAndIncrement();
        String text = teile[1].strip();

        // Erst den Status senden, dann planen: Die Statusnachricht wird später bearbeitet oder gelöscht.
        JsonNode status = statusSenden(chatId, id, "⏳ Erinnerung in " + Dauer.text(dauer.get()) + ": " + text);
        Erinnerung erinnerung = new Erinnerung(id, chatId, text, status.get("message_id").asLong(),
                uhr.instant().plus(dauer.get()));
        offen.put(id, erinnerung);
        erinnerung.abbruch = planer.planen(dauer.get(), () -> ausfuehren(id));
    }
    // #endregion erinnere

    private JsonNode statusSenden(long chatId, long id, String text) throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("chat_id", chatId).put("text", text);
        parameter.set("reply_markup", abbrechenTaste(id));
        return client.aufrufen("sendMessage", parameter);
    }

    private static ObjectNode abbrechenTaste(long id) {
        return Tastaturen.inline(List.of(List.of(Taste.daten("Abbrechen", "abbrechen:" + id))));
    }

    private void liste(long chatId) throws IOException, InterruptedException {
        List<Erinnerung> eigene = offen.values().stream()
                .filter(e -> e.chatId == chatId)
                .sorted(Comparator.comparing(e -> e.faelligUm))
                .toList();
        if (eigene.isEmpty()) {
            senden(chatId, "Keine offenen Erinnerungen.");
            return;
        }
        StringBuilder text = new StringBuilder("Offene Erinnerungen:");
        for (Erinnerung e : eigene) {
            Duration rest = Duration.between(uhr.instant(), e.faelligUm);
            text.append("\n- ").append(e.text).append(" (in ")
                    .append(Dauer.text(rest.isNegative() ? Duration.ZERO.plusSeconds(1) : rest)).append(")");
        }
        senden(chatId, text.toString());
    }

    // #region ausfuehren
    /** Läuft im Planer-Thread, wenn die Zeit um ist. */
    private void ausfuehren(long id) {
        try {
            Erinnerung erinnerung = offen.remove(id);
            if (erinnerung == null) {
                return; // inzwischen abgebrochen
            }
            ausgeloest.put(id, erinnerung);
            // Der Status ist erledigt: aufräumen. Ist er schon weg oder zu alt, ist das kein Grund aufzuhören.
            loeschen(erinnerung.chatId, erinnerung.statusNachrichtId);

            ObjectNode parameter = TelegramClient.parameter()
                    .put("chat_id", erinnerung.chatId).put("text", "⏰ Erinnerung: " + erinnerung.text);
            parameter.set("reply_markup", Tastaturen.inline(List.of(List.of(
                    Taste.daten("Erledigt", "erledigt:" + id), Taste.daten("Später", "spaeter:" + id)))));
            // Eine neue Nachricht, denn nur sie benachrichtigt den Nutzer. Eine Bearbeitung bleibt still.
            client.aufrufen("sendMessage", parameter);
        } catch (IOException | RuntimeException e) {
            System.err.println("Erinnerung " + id + " fehlgeschlagen: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    // #endregion ausfuehren

    private void klick(JsonNode abfrage) throws IOException, InterruptedException {
        String[] daten = abfrage.path("data").asText().split(":", 2);
        long id = daten.length == 2 && daten[1].matches("\\d{1,18}") ? Long.parseLong(daten[1]) : -1;
        long chatId = abfrage.path("message").path("chat").path("id").asLong();
        long nachrichtId = abfrage.path("message").path("message_id").asLong();
        String antwort = "Diese Erinnerung gibt es nicht mehr.";

        switch (daten[0]) {
            case "abbrechen" -> {
                Erinnerung erinnerung = offen.get(id);
                if (erinnerung != null && erinnerung.chatId == chatId && offen.remove(id, erinnerung)) {
                    erinnerung.abbruch.run();
                    textAendern(chatId, nachrichtId, "❌ Abgebrochen: " + erinnerung.text, null);
                    antwort = "Abgebrochen";
                }
            }
            case "erledigt" -> {
                Erinnerung erinnerung = ausgeloest.get(id);
                if (erinnerung != null && erinnerung.chatId == chatId && ausgeloest.remove(id, erinnerung)) {
                    textAendern(chatId, nachrichtId, "✅ Erledigt: " + erinnerung.text, null);
                    antwort = "Erledigt";
                }
            }
            case "spaeter" -> {
                Erinnerung erinnerung = ausgeloest.get(id);
                if (erinnerung != null && erinnerung.chatId == chatId && ausgeloest.remove(id, erinnerung)) {
                    verschieben(erinnerung, nachrichtId);
                    antwort = "In " + Dauer.text(SPAETER) + " noch einmal";
                }
            }
            default -> antwort = "Diese Taste ist veraltet.";
        }
        client.aufrufen("answerCallbackQuery",
                TelegramClient.parameter().put("callback_query_id", abfrage.path("id").asText()).put("text", antwort));
    }

    // #region verschieben
    /** Die Erinnerungsnachricht wird wieder zum Status: neuer Text, neue "Abbrechen"-Taste, neue Uhr. */
    private void verschieben(Erinnerung erinnerung, long nachrichtId) throws IOException, InterruptedException {
        erinnerung.statusNachrichtId = nachrichtId;
        erinnerung.faelligUm = uhr.instant().plus(SPAETER);
        textAendern(erinnerung.chatId, nachrichtId,
                "⏳ Erinnerung in " + Dauer.text(SPAETER) + ": " + erinnerung.text, abbrechenTaste(erinnerung.id));
        offen.put(erinnerung.id, erinnerung);
        erinnerung.abbruch = planer.planen(SPAETER, () -> ausfuehren(erinnerung.id));
    }
    // #endregion verschieben

    // #region aendern
    /**
     * Ändert den Text einer eigenen Nachricht. Ohne {@code tasten} verschwinden die Tasten der Nachricht.
     * Ein 400er (z. B. "message is not modified" oder Nachricht schon gelöscht) ist hier kein Grund zum Abbruch.
     */
    private void textAendern(long chatId, long nachrichtId, String text, ObjectNode tasten)
            throws IOException, InterruptedException {
        ObjectNode parameter = TelegramClient.parameter()
                .put("chat_id", chatId).put("message_id", nachrichtId).put("text", text);
        if (tasten != null) {
            parameter.set("reply_markup", tasten);
        }
        try {
            client.aufrufen("editMessageText", parameter);
        } catch (TelegramException e) {
            if (e.errorCode() != 400) {
                throw e;
            }
        }
    }
    // #endregion aendern

    // #region loeschen
    /** Löscht eine Nachricht. Nachrichten über 48 Stunden oder schon gelöschte lassen sich nicht löschen (400). */
    private void loeschen(long chatId, long nachrichtId) throws IOException, InterruptedException {
        try {
            client.aufrufen("deleteMessage",
                    TelegramClient.parameter().put("chat_id", chatId).put("message_id", nachrichtId));
        } catch (TelegramException e) {
            if (e.errorCode() != 400) {
                throw e;
            }
        }
    }
    // #endregion loeschen

    private void senden(long chatId, String text) throws IOException, InterruptedException {
        client.aufrufen("sendMessage", TelegramClient.parameter().put("chat_id", chatId).put("text", text));
    }
}
