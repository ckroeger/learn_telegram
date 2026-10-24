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

    /** Eine Taste unter einer Nachricht: entweder mit {@code callback_data} oder mit {@code url}. */
    public record Taste(String text, String callbackData, String url) {

        public Taste {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Eine Taste braucht einen Text");
            }
            if ((callbackData == null) == (url == null)) {
                throw new IllegalArgumentException("Genau eines von callback_data und url muss gesetzt sein");
            }
            if (callbackData != null) {
                int bytes = callbackData.getBytes(StandardCharsets.UTF_8).length;
                if (bytes < 1 || bytes > 64) {
                    throw new IllegalArgumentException("callback_data muss 1 bis 64 Bytes lang sein, ist aber " + bytes);
                }
            }
        }

        public static Taste daten(String text, String callbackData) {
            return new Taste(text, callbackData, null);
        }

        public static Taste link(String text, String url) {
            return new Taste(text, null, url);
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
                } else {
                    knopf.put("url", taste.url());
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
