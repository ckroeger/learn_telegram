package telegram.lernen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Holt Updates per Long Polling ab und übergibt sie nacheinander an einen Handler. */
public class UpdatePoller {

    /** Warten, damit Tests nicht echte Sekunden schlafen müssen. */
    interface Schlafen {
        void sekunden(long sekunden) throws InterruptedException;
    }

    private final TelegramClient api;
    private final List<String> allowedUpdates;
    private final Schlafen schlafen;
    private long offset = 0;

    public UpdatePoller(TelegramClient api, List<String> allowedUpdates) {
        this(api, allowedUpdates, sekunden -> Thread.sleep(sekunden * 1000));
    }

    UpdatePoller(TelegramClient api, List<String> allowedUpdates, Schlafen schlafen) {
        this.api = api;
        this.allowedUpdates = allowedUpdates;
        this.schlafen = schlafen;
    }

    // #region schleife
    /** Läuft, bis der Thread unterbrochen wird (dann fliegt eine InterruptedException). */
    public void laufen(Consumer<JsonNode> handler) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            for (JsonNode update : holeUpdates()) {
                try {
                    handler.accept(update);
                } catch (RuntimeException e) {
                    // Ein kaputtes Update darf den Bot nicht stoppen und nicht endlos neu geliefert werden.
                    System.err.println("Fehler im Handler: " + e);
                }
                // Mit offset = update_id + 1 bestätigen wir dieses und alle früheren Updates.
                offset = update.get("update_id").asLong() + 1;
            }
        }
    }
    // #endregion schleife

    // #region abfrage
    private JsonNode holeUpdates() throws InterruptedException {
        ObjectNode parameter = TelegramClient.parameter().put("offset", offset).put("timeout", 30);
        // Immer mitsenden: Fehlt das Feld, gilt die vorherige Einstellung weiter. Eine leere Liste heißt "Standard".
        ArrayNode liste = parameter.putArray("allowed_updates");
        allowedUpdates.forEach(liste::add);
        try {
            return api.aufrufen("getUpdates", parameter);
        } catch (TelegramException e) {
            if (e.retryAfter() > 0) {
                // HTTP 429: so lange warten, wie Telegram verlangt, dann neu fragen.
                schlafen.sekunden(e.retryAfter());
                return JsonNodeFactory.instance.arrayNode();
            }
            throw e;
        } catch (IOException e) {
            // Nur der Typ, nicht die Meldung: sie könnte die URL mit dem Token enthalten.
            System.err.println("Netzwerkfehler (" + e.getClass().getSimpleName() + "), neuer Versuch in 5 Sekunden");
            schlafen.sekunden(5);
            return JsonNodeFactory.instance.arrayNode();
        }
    }
    // #endregion abfrage
}
