package telegram.lernen.miniapp;

import java.time.Clock;
import java.util.List;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/**
 * Startet Server und Bot der Mini App in einem Programm.
 *
 * <p>Vorher läuft ein Tunnel (siehe Lektion), und {@code MINI_APP_URL} nennt dessen HTTPS-Adresse. Für diese Lektion
 * gehört {@code TELEGRAM_BOT_TOKEN} dem zweiten Bot, dem für die Mini App. Der Hash der Daten hängt am Token des Bots,
 * der die Mini App geöffnet hat.
 */
public final class MiniApp {

    private MiniApp() {
    }

    // #region start
    public static void main(String[] args) throws Exception {
        String url = System.getenv("MINI_APP_URL");
        if (url == null || !url.startsWith("https://")) {
            System.err.println("MINI_APP_URL fehlt oder beginnt nicht mit https:// (Adresse des Tunnels)");
            System.exit(1);
        }
        url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        InitDataPruefer pruefer = new InitDataPruefer(System.getenv("TELEGRAM_BOT_TOKEN"), InitDataPruefer.STANDARD_MAX_ALTER);

        try (MiniAppServer server = MiniAppServer.starten(port, pruefer, client, Clock.systemUTC())) {
            MiniAppBot bot = new MiniAppBot(client, eigenerName, url);
            bot.menueSetzen();
            System.out.println("@" + eigenerName + " läuft, die Mini App liegt unter " + url + " (lokal Port " + server.port() + ")");
            new UpdatePoller(client, List.of("message")).laufen(bot.alsHandler());
        }
    }
    // #endregion start
}
