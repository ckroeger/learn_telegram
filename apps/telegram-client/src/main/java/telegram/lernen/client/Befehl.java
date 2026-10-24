package telegram.lernen.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;

/** Ein Befehl am Anfang einer Nachricht, z. B. {@code /echo Hallo} oder {@code /hilfe@mein_bot}. */
public record Befehl(String name, String argumente) {

    // #region befehl
    /** Beginnt die Nachricht mit einem Befehl, egal an welchen Bot er gerichtet ist? */
    public static boolean beginntMitBefehl(JsonNode nachricht) {
        for (JsonNode entitaet : nachricht.path("entities")) {
            if (entitaet.path("type").asText().equals("bot_command") && entitaet.path("offset").asInt() == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Telegram markiert Befehle als Entität {@code bot_command}. In Gruppen kann ein Befehl mit {@code @botname}
     * an einen bestimmten Bot gerichtet sein. Ist er an einen anderen Bot gerichtet, ist das Ergebnis leer.
     */
    public static Optional<Befehl> aus(JsonNode nachricht, String eigenerName) {
        if (!beginntMitBefehl(nachricht)) {
            return Optional.empty();
        }
        String text = nachricht.path("text").asText("");
        int laenge = nachricht.path("entities").get(0).path("length").asInt();
        String wort = text.substring(1, laenge);
        int at = wort.indexOf('@');
        if (at >= 0) {
            if (!wort.substring(at + 1).equalsIgnoreCase(eigenerName)) {
                return Optional.empty();
            }
            wort = wort.substring(0, at);
        }
        return Optional.of(new Befehl(wort.toLowerCase(Locale.ROOT), text.substring(laenge).trim()));
    }
    // #endregion befehl
}
