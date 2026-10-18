package telegram.lernen.einstieg;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Fragt mit {@code getMe} nach dem Profil des Bots und gibt es aus. */
public class WerBinIch {

    public static void main(String[] args) throws Exception {
        System.out.println(profil(BotApi.ausUmgebung()));
    }

    // #region profil
    static String profil(BotApi api) throws IOException, InterruptedException {
        JsonNode bot = api.aufrufen("getMe");
        return "Bot %s (@%s), id %d".formatted(
                bot.get("first_name").asText(),
                bot.get("username").asText(),
                bot.get("id").asLong());
    }
    // #endregion profil
}
