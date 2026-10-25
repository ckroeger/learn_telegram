package telegram.lernen.miniapp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * Geprüfte {@code initData}. Nur Objekte dieser Klasse darf der Server als "kommt von Telegram" behandeln.
 *
 * @param felder   alle Felder außer {@code hash}, schon URL-dekodiert
 * @param authDate Zeitpunkt, an dem der Nutzer die Mini App geöffnet hat
 * @param queryId  nur vorhanden, wenn die Mini App über die Menütaste oder eine Inline-Taste geöffnet wurde
 * @param user     das Feld {@code user} als JSON oder ein fehlender Knoten
 */
public record GepruefteInitData(Map<String, String> felder, Instant authDate, String queryId, JsonNode user) {

    public long userId() {
        return user.path("id").asLong();
    }

    public String vorname() {
        return user.path("first_name").asText("");
    }

    /** Nicht jeder Nutzer hat einen Benutzernamen. */
    public String username() {
        return user.hasNonNull("username") ? user.get("username").asText() : null;
    }

    public boolean kannAntworten() {
        return queryId != null;
    }
}
