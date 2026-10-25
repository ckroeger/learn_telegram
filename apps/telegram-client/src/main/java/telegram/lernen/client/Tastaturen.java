package telegram.lernen.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Baut die {@code reply_markup}-Objekte für Tastaturen. Das Ergebnis kommt in den Parameter {@code reply_markup}. */
public final class Tastaturen {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Tastaturen() {
    }

    /**
     * Eine Taste unter einer Nachricht mit genau einer Aktion: {@code callback_data}, {@code url} oder ein Wechsel in
     * den Inline-Modus ({@code switch_inline_query} wählt einen Chat, {@code switch_inline_query_current_chat} bleibt im
     * aktuellen Chat). Die Abfrage für den Inline-Modus darf leer sein, dann wird nur der Botname eingesetzt.
     */
    public record Taste(String text, String callbackData, String url, String inlineAbfrage, String inlineAbfrageHier) {

        public Taste {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Eine Taste braucht einen Text");
            }
            int aktionen = (callbackData != null ? 1 : 0) + (url != null ? 1 : 0)
                    + (inlineAbfrage != null ? 1 : 0) + (inlineAbfrageHier != null ? 1 : 0);
            if (aktionen != 1) {
                throw new IllegalArgumentException("Genau eine Aktion muss gesetzt sein (callback_data, url oder Inline-Abfrage)");
            }
            if (callbackData != null) {
                int bytes = callbackData.getBytes(StandardCharsets.UTF_8).length;
                if (bytes < 1 || bytes > 64) {
                    throw new IllegalArgumentException("callback_data muss 1 bis 64 Bytes lang sein, ist aber " + bytes);
                }
            }
        }

        public Taste(String text, String callbackData, String url) {
            this(text, callbackData, url, null, null);
        }

        public static Taste daten(String text, String callbackData) {
            return new Taste(text, callbackData, null);
        }

        public static Taste link(String text, String url) {
            return new Taste(text, null, url);
        }

        /** Fragt nach einem Chat und setzt dort {@code @bot abfrage} ins Eingabefeld. */
        public static Taste inlineInAnderemChat(String text, String abfrage) {
            return new Taste(text, null, null, abfrage, null);
        }

        /** Setzt {@code @bot abfrage} ins Eingabefeld des aktuellen Chats. */
        public static Taste inlineHier(String text, String abfrage) {
            return new Taste(text, null, null, null, abfrage);
        }
    }

    // #region inline
    /** Tasten direkt unter der Nachricht. Ein Klick auf eine {@code callback_data}-Taste erzeugt ein Update. */
    public static ObjectNode inline(List<List<Taste>> zeilen) {
        ObjectNode markup = JSON.createObjectNode();
        ArrayNode alleZeilen = markup.putArray("inline_keyboard");
        for (List<Taste> zeile : zeilen) {
            ArrayNode json = alleZeilen.addArray();
            for (Taste taste : zeile) {
                ObjectNode knopf = json.addObject().put("text", taste.text());
                if (taste.callbackData() != null) {
                    knopf.put("callback_data", taste.callbackData());
                } else if (taste.url() != null) {
                    knopf.put("url", taste.url());
                } else if (taste.inlineAbfrage() != null) {
                    knopf.put("switch_inline_query", taste.inlineAbfrage());
                } else {
                    knopf.put("switch_inline_query_current_chat", taste.inlineAbfrageHier());
                }
            }
        }
        return markup;
    }
    // #endregion inline

    // #region antwort
    /** Tastatur anstelle der Buchstabentastatur. Ein Druck sendet den Text der Taste als normale Nachricht. */
    public static ObjectNode antwort(List<List<String>> zeilen) {
        ObjectNode markup = JSON.createObjectNode();
        ArrayNode alleZeilen = markup.putArray("keyboard");
        for (List<String> zeile : zeilen) {
            ArrayNode json = alleZeilen.addArray();
            zeile.forEach(text -> json.addObject().put("text", text));
        }
        markup.put("resize_keyboard", true);
        return markup;
    }
    // #endregion antwort

    // #region entfernen
    /** Blendet eine Antwort-Tastatur wieder aus. */
    public static ObjectNode entfernen() {
        return JSON.createObjectNode().put("remove_keyboard", true);
    }
    // #endregion entfernen
}
