package telegram.lernen.einstieg;

import java.io.IOException;

/** Lösung der Übung: die lange Beschreibung des Bots setzen und zurücklesen. */
public class UebungLoesung {

    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "Dieser Bot gehört zur Lernseite zur Telegram-API.";
        System.out.println(beschreibungSetzen(BotApi.ausUmgebung(), text));
    }

    // #region loesung
    static String beschreibungSetzen(BotApi api, String text) throws IOException, InterruptedException {
        api.aufrufen("setMyDescription", BotApi.parameter().put("description", text));
        return api.aufrufen("getMyDescription").get("description").asText();
    }
    // #endregion loesung
}
