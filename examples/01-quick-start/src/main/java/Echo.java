import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Echo-Bot in einer einzigen Datei: {@code java Echo.java}.
 *
 * <p>Nur für den Quick-Start: das JSON wird mit regulären Ausdrücken gelesen. Ab Lektion 2 liest Jackson es richtig.
 */
public class Echo {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("TELEGRAM_BOT_TOKEN ist nicht gesetzt (siehe Kapitel Vorbereitung).");
            System.exit(1);
        }
        new Echo(token, "https://api.telegram.org").run();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiUrl;

    Echo(String token, String baseUrl) {
        // Der Token steckt im Pfad der URL: diese URL nie ausgeben oder loggen.
        this.apiUrl = baseUrl + "/bot" + token + "/";
    }

    void run() throws IOException, InterruptedException {
        String me = aufrufen("getMe", "{}");
        System.out.println("Bot läuft als @" + finden(me, "\"username\":\"([^\"]+)\""));

        long offset = 0;
        while (true) {
            // Long Polling: Telegram antwortet, sobald ein Update da ist, spätestens nach 30 Sekunden.
            String antwort = aufrufen("getUpdates", "{\"offset\":" + offset + ",\"timeout\":30}");

            // Das erste Stück ist der Antwort-Kopf, danach beginnt je Stück ein Update.
            String[] stuecke = antwort.split("\\{\"update_id\":");
            for (int i = 1; i < stuecke.length; i++) {
                String update = stuecke[i];
                offset = Long.parseLong(finden(update, "^(\\d+)")) + 1;

                String chatId = finden(update, "\"chat\":\\{\"id\":(-?\\d+)");
                String text = finden(update, "\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
                if (chatId != null && text != null) {
                    // Der Text bleibt so, wie er im JSON stand (mit Escapes), und ist damit wieder gültiges JSON.
                    aufrufen("sendMessage", "{\"chat_id\":" + chatId + ",\"text\":\"" + text + "\"}");
                }
            }
        }
    }

    private String aufrufen(String methode, String json) throws IOException, InterruptedException {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(apiUrl + methode))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        String antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString()).body();
        if (!antwort.contains("\"ok\":true")) {
            throw new IllegalStateException("Telegram meldet einen Fehler: " + antwort);
        }
        return antwort;
    }

    private static String finden(String text, String muster) {
        Matcher treffer = Pattern.compile(muster).matcher(text);
        return treffer.find() ? treffer.group(1) : null;
    }
}
