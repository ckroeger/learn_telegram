package telegram.lernen.nachrichten;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Lösung der Übung: Code als formatierten Block senden, sicher gegen Sonderzeichen. */
public class UebungLoesung {

    public static void main(String[] args) throws Exception {
        long chatId = Long.parseLong(args[0]);
        String code = args.length > 1 ? args[1] : "if (a < b && c > d) { return; }";
        codeSenden(new Nachrichten(BotApi.ausUmgebung()), chatId, code);
    }

    // #region loesung
    static JsonNode codeSenden(Nachrichten nachrichten, long chatId, String code)
            throws IOException, InterruptedException {
        String html = "<pre><code class=\"language-java\">" + Nachrichten.htmlEscape(code) + "</code></pre>";
        return nachrichten.sendenHtml(chatId, html);
    }
    // #endregion loesung
}
