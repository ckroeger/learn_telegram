package telegram.lernen.nachrichten;

import com.fasterxml.jackson.databind.JsonNode;

/** Schickt eine kleine Folge von Nachrichten an einen Chat: {@code NachrichtSenden <chat-id> [text]}. */
public class NachrichtSenden {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Aufruf: NachrichtSenden <chat-id> [text]  (die Chat-ID zeigt getUpdates, siehe Quick-Start)");
            System.exit(2);
        }
        long chatId = Long.parseLong(args[0]);
        String eingabe = args.length > 1 ? args[1] : "a < b & c";
        Nachrichten nachrichten = new Nachrichten(BotApi.ausUmgebung());

        JsonNode erste = nachrichten.senden(chatId, "Hallo von Java");
        nachrichten.antworten(chatId, erste.get("message_id").asLong(), "Das ist eine Antwort auf die erste Nachricht");
        nachrichten.sendenHtml(chatId, "<b>Du hast geschrieben:</b> " + Nachrichten.htmlEscape(eingabe));
        nachrichten.tippen(chatId);
    }
}
