package telegram.lernen.client;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Baut Ergebnisse und Parameter für {@code answerInlineQuery}. */
public final class Inline {

    /** Mehr Ergebnisse nimmt Telegram pro Antwort nicht an. */
    public static final int MAX_ERGEBNISSE = 50;

    private Inline() {
    }

    // #region artikel
    /**
     * Ein Ergebnis vom Typ {@code article}: ein Eintrag mit Titel und Beschreibung in der Ergebnisliste. Tippt der
     * Nutzer darauf, wird {@code text} als normale Nachricht in den Chat gesendet.
     */
    public static ObjectNode artikel(String id, String titel, String beschreibung, String text) {
        int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > 64) {
            throw new IllegalArgumentException("Die ID eines Ergebnisses muss 1 bis 64 Bytes lang sein, ist aber " + bytes);
        }
        if (text.isEmpty() || text.length() > 4096) {
            throw new IllegalArgumentException("Der Nachrichtentext braucht 1 bis 4096 Zeichen");
        }
        ObjectNode ergebnis = TelegramClient.parameter().put("type", "article").put("id", id).put("title", titel);
        if (beschreibung != null && !beschreibung.isEmpty()) {
            ergebnis.put("description", beschreibung);
        }
        ergebnis.putObject("input_message_content").put("message_text", text);
        return ergebnis;
    }
    // #endregion artikel

    // #region antwort
    /** Die Parameter von {@code answerInlineQuery}: Anfrage-ID und bis zu 50 Ergebnisse. Weitere Felder setzt der Aufrufer. */
    public static ObjectNode antwort(String anfrageId, List<ObjectNode> ergebnisse) {
        if (ergebnisse.size() > MAX_ERGEBNISSE) {
            throw new IllegalArgumentException("Höchstens " + MAX_ERGEBNISSE + " Ergebnisse, übergeben wurden " + ergebnisse.size());
        }
        ObjectNode parameter = TelegramClient.parameter().put("inline_query_id", anfrageId);
        ArrayNode liste = parameter.putArray("results");
        ergebnisse.forEach(liste::add);
        return parameter;
    }
    // #endregion antwort
}
