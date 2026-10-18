package telegram.lernen.einstieg;

import java.io.IOException;

/** Setzt die Kurzbeschreibung des Bots und liest sie zur Kontrolle zurück. */
public class BotProfil {

    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "Mein Lern-Bot";
        System.out.println(kurzbeschreibungSetzen(BotApi.ausUmgebung(), text));
    }

    // #region kurzbeschreibung
    static String kurzbeschreibungSetzen(BotApi api, String text) throws IOException, InterruptedException {
        api.aufrufen("setMyShortDescription", BotApi.parameter().put("short_description", text));
        return api.aufrufen("getMyShortDescription").get("short_description").asText();
    }
    // #endregion kurzbeschreibung
}
