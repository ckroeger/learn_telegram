package telegram.lernen.nachrichten;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Nachrichten senden: Text, Formatierung, Antworten, Optionen, Chat-Aktion, Weiterleiten und Kopieren. */
public class Nachrichten {

    /** Längstes erlaubtes {@code text}-Feld von sendMessage. */
    public static final int MAX_LAENGE = 4096;

    private final BotApi api;

    public Nachrichten(BotApi api) {
        this.api = api;
    }

    // #region einfach
    /** Gibt die gesendete Nachricht zurück, u. a. mit {@code message_id}. */
    public JsonNode senden(long chatId, String text) throws IOException, InterruptedException {
        return api.aufrufen("sendMessage", BotApi.parameter().put("chat_id", chatId).put("text", text));
    }
    // #endregion einfach

    // #region formatiert
    public JsonNode sendenHtml(long chatId, String html) throws IOException, InterruptedException {
        return api.aufrufen("sendMessage",
                BotApi.parameter().put("chat_id", chatId).put("text", html).put("parse_mode", "HTML"));
    }

    /** Macht beliebigen Text zu einem sicheren Teil einer HTML-Nachricht. */
    public static String htmlEscape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    // #endregion formatiert

    // #region antwort
    public JsonNode antworten(long chatId, long nachrichtenId, String text) throws IOException, InterruptedException {
        ObjectNode parameter = BotApi.parameter().put("chat_id", chatId).put("text", text);
        parameter.putObject("reply_parameters").put("message_id", nachrichtenId);
        return api.aufrufen("sendMessage", parameter);
    }
    // #endregion antwort

    // #region optionen
    /** Ohne Ton, ohne Link-Vorschau und gegen Weiterleiten und Speichern geschützt. */
    public JsonNode diskret(long chatId, String text) throws IOException, InterruptedException {
        ObjectNode parameter = BotApi.parameter()
                .put("chat_id", chatId)
                .put("text", text)
                .put("disable_notification", true)
                .put("protect_content", true);
        parameter.putObject("link_preview_options").put("is_disabled", true);
        return api.aufrufen("sendMessage", parameter);
    }
    // #endregion optionen

    // #region chataktion
    /** Zeigt "schreibt ..." an, bis die nächste Nachricht kommt, höchstens 5 Sekunden. */
    public void tippen(long chatId) throws IOException, InterruptedException {
        api.aufrufen("sendChatAction", BotApi.parameter().put("chat_id", chatId).put("action", "typing"));
    }
    // #endregion chataktion

    // #region weiterleiten
    /** Leitet eine Nachricht weiter. Sie behält den Hinweis auf den Absender. */
    public JsonNode weiterleiten(long zielChat, long vonChat, long nachrichtenId)
            throws IOException, InterruptedException {
        return api.aufrufen("forwardMessage", BotApi.parameter()
                .put("chat_id", zielChat).put("from_chat_id", vonChat).put("message_id", nachrichtenId));
    }

    /** Kopiert eine Nachricht ohne Hinweis auf das Original und liefert die neue message_id. */
    public long kopieren(long zielChat, long vonChat, long nachrichtenId) throws IOException, InterruptedException {
        JsonNode ergebnis = api.aufrufen("copyMessage", BotApi.parameter()
                .put("chat_id", zielChat).put("from_chat_id", vonChat).put("message_id", nachrichtenId));
        return ergebnis.get("message_id").asLong();
    }
    // #endregion weiterleiten

    // #region langeTexte
    /** Schickt einen langen Text als mehrere Nachrichten. */
    public List<JsonNode> sendenLang(long chatId, String text) throws IOException, InterruptedException {
        List<JsonNode> gesendet = new ArrayList<>();
        for (String teil : teile(text, MAX_LAENGE)) {
            gesendet.add(senden(chatId, teil));
        }
        return gesendet;
    }

    /** Teilt bevorzugt an Zeilenumbrüchen, sonst hart nach {@code max} Zeichen, nie mitten in einem Emoji. */
    static List<String> teile(String text, int max) {
        List<String> teile = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int schnitt = rest.lastIndexOf('\n', max);
            if (schnitt > 0) {
                teile.add(rest.substring(0, schnitt));
                rest = rest.substring(schnitt + 1);
            } else {
                schnitt = Character.isLowSurrogate(rest.charAt(max)) ? max - 1 : max;
                teile.add(rest.substring(0, schnitt));
                rest = rest.substring(schnitt);
            }
        }
        if (!rest.isEmpty()) {
            teile.add(rest);
        }
        return teile;
    }
    // #endregion langeTexte
}
