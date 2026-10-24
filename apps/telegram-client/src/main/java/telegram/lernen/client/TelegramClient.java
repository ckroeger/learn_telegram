package telegram.lernen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.time.Duration;

/**
 * Kleiner Client für die Bot API: ein POST mit JSON je Methode, Fehler als {@link TelegramException}.
 * Das ist die Klasse {@code BotApi} aus den Lektionen 2 bis 4, jetzt als gemeinsames Modul für die Apps.
 */
public class TelegramClient {

    private static final String STANDARD_URL = "https://api.telegram.org";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiUrl;
    private final String dateiUrl;

    public TelegramClient(String token, String baseUrl) {
        // Der Token steckt im Pfad der URL: diese URL nie ausgeben oder loggen.
        this.apiUrl = baseUrl + "/bot" + token + "/";
        this.dateiUrl = baseUrl + "/file/bot" + token + "/";
    }

    /** Token aus {@code TELEGRAM_BOT_TOKEN}; {@code TELEGRAM_API_URL} ist nur für Tests gedacht. */
    public static TelegramClient ausUmgebung() {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN ist nicht gesetzt (siehe Kapitel Vorbereitung)");
        }
        return new TelegramClient(token, System.getenv().getOrDefault("TELEGRAM_API_URL", STANDARD_URL));
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

    // #region hochladen
    /**
     * Ruft eine Methode mit Dateien auf, z. B. {@code sendDocument}. Statt JSON geht der Aufruf als
     * {@code multipart/form-data}: die Parameter als Textfelder, jede Datei als eigener Teil.
     */
    public JsonNode hochladen(String methode, ObjectNode parameter, Dateianhang... dateien)
            throws IOException, InterruptedException {
        Multipart multipart = Multipart.aus(parameter, List.of(dateien));
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(apiUrl + methode))
                .header("Content-Type", multipart.contentType())
                .timeout(Duration.ofMinutes(5))
                .POST(multipart.koerper())
                .build();
        HttpResponse<String> antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(antwort.body());
        if (!json.path("ok").asBoolean()) {
            throw new TelegramException(json);
        }
        return json.get("result");
    }
    // #endregion hochladen

    // #region herunterladen
    /**
     * Lädt eine Datei herunter, deren {@code file_path} {@code getFile} geliefert hat. Telegram erlaubt das für
     * Dateien bis 20 MB. Die Adresse enthält den Token, sie darf nie ausgegeben werden.
     */
    public void herunterladen(String dateiPfad, Path ziel) throws IOException, InterruptedException {
        // Der Pfad kommt von Telegram, aber er wird in eine URL eingesetzt: nichts anderes als Pfadzeichen zulassen.
        if (!dateiPfad.matches("[A-Za-z0-9_./-]+") || dateiPfad.contains("..") || dateiPfad.startsWith("/")) {
            throw new IllegalArgumentException("Ungültiger file_path");
        }
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(dateiUrl + dateiPfad))
                .timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream eingang = antwort.body()) {
            if (antwort.statusCode() != 200) {
                throw new IOException("Download fehlgeschlagen: HTTP " + antwort.statusCode());
            }
            Files.copy(eingang, ziel, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    // #endregion herunterladen
}
