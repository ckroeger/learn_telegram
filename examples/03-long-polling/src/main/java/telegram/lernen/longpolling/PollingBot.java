package telegram.lernen.longpolling;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Gibt jedes eingehende Update in einer Zeile aus. Beenden mit Strg+C. */
public class PollingBot {

    public static void main(String[] args) throws Exception {
        System.out.println("Warte auf Updates (Strg+C beendet) ...");
        new UpdatePoller(BotApi.ausUmgebung(), List.of()).laufen(update -> System.out.println(beschreibe(update)));
    }

    // #region beschreibe
    /** Jedes Update hat genau ein Feld außer update_id; sein Name ist der Typ, z. B. "message". */
    static String beschreibe(JsonNode update) {
        String typ = typVon(update);
        String text = update.path(typ).path("text").asText("(kein Text)");
        return "Update %d (%s): %s".formatted(update.get("update_id").asLong(), typ, text);
    }

    private static String typVon(JsonNode update) {
        var namen = update.fieldNames();
        while (namen.hasNext()) {
            String name = namen.next();
            if (!name.equals("update_id")) {
                return name;
            }
        }
        return "?";
    }
    // #endregion beschreibe
}
