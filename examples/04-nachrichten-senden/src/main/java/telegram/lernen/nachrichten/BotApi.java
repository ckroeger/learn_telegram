package telegram.lernen.nachrichten;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Kleiner Client für die Bot API: ein POST mit JSON je Methode, Fehler als {@link TelegramException}. */
public class BotApi {

    private static final String STANDARD_URL = "https://api.telegram.org";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiUrl;

    public BotApi(String token, String baseUrl) {
        // Der Token steckt im Pfad der URL: diese URL nie ausgeben oder loggen.
        this.apiUrl = baseUrl + "/bot" + token + "/";
    }

    /** Token aus {@code TELEGRAM_BOT_TOKEN}; {@code TELEGRAM_API_URL} ist nur für Tests gedacht. */
    public static BotApi ausUmgebung() {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN ist nicht gesetzt (siehe Kapitel Vorbereitung)");
        }
        return new BotApi(token, System.getenv().getOrDefault("TELEGRAM_API_URL", STANDARD_URL));
    }

    public static ObjectNode parameter() {
        return JSON.createObjectNode();
    }

    public JsonNode aufrufen(String methode) throws IOException, InterruptedException {
        return aufrufen(methode, parameter());
    }

    // #region aufruf
    public JsonNode aufrufen(String methode, ObjectNode parameter) throws IOException, InterruptedException {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(apiUrl + methode))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(parameter.toString()))
                .build();
        HttpResponse<String> antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString());

        JsonNode json = JSON.readTree(antwort.body());
        if (!json.path("ok").asBoolean()) {
            throw new TelegramException(json);
        }
        return json.get("result");
    }
    // #endregion aufruf
}
