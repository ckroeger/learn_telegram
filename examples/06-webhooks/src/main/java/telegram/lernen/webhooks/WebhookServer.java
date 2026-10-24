package telegram.lernen.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Nimmt Updates von Telegram per Webhook entgegen.
 *
 * <p>Der Server spricht nur HTTP. Telegram verlangt HTTPS: In der Praxis steht davor ein Reverse Proxy
 * oder ein Tunnel, der TLS übernimmt und an diesen Server weiterreicht.
 */
public final class WebhookServer implements AutoCloseable {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    /** Updates sind klein. Alles darüber ist kein Update von Telegram. */
    static final int MAX_BYTES = 1_000_000;
    static final int WARTESCHLANGE = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ThreadPoolExecutor verarbeitung;
    private final byte[] secret;
    private final Consumer<JsonNode> handler;

    private WebhookServer(HttpServer server, String secret, Consumer<JsonNode> handler) {
        this.server = server;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
        // Ein Thread, begrenzte Warteschlange: Updates werden der Reihe nach verarbeitet, und wenn der Bot
        // nicht hinterherkommt, antwortet der Server mit 503, statt unbegrenzt Speicher zu belegen.
        this.verarbeitung = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(WARTESCHLANGE));
    }

    /** Startet den Server. Port 0 wählt einen freien Port. */
    public static WebhookServer starten(int port, String pfad, String secret, Consumer<JsonNode> handler)
            throws IOException {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Ohne secret_token kann der Server nicht prüfen, wer ihm Updates schickt");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        WebhookServer webhook = new WebhookServer(server, secret, handler);
        server.createContext(pfad, webhook::anfrage);
        server.start();
        return webhook;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // #region anfrage
    private void anfrage(HttpExchange austausch) throws IOException {
        try (austausch) {
            if (!austausch.getRequestMethod().equals("POST")) {
                austausch.getResponseHeaders().set("Allow", "POST");
                antworten(austausch, 405);
                return;
            }
            // Wer das Geheimnis nicht kennt, hat dieses Update nicht von Telegram bekommen.
            if (!gleich(secret, austausch.getRequestHeaders().getFirst(SECRET_HEADER))) {
                antworten(austausch, 403);
                return;
            }
            byte[] koerper = austausch.getRequestBody().readNBytes(MAX_BYTES + 1);
            if (koerper.length > MAX_BYTES) {
                antworten(austausch, 413);
                return;
            }
            JsonNode update;
            try {
                update = JSON.readTree(koerper);
            } catch (IOException e) {
                antworten(austausch, 400);
                return;
            }
            if (update == null || !update.has("update_id")) {
                antworten(austausch, 400);
                return;
            }
            try {
                // Nur einreihen und sofort antworten. Telegram wiederholt Anfragen, die nicht mit 2xx beantwortet
                // werden, die eigentliche Arbeit darf also nicht in der Anfrage stattfinden.
                verarbeitung.execute(() -> verarbeiten(update));
            } catch (RejectedExecutionException e) {
                antworten(austausch, 503);
                return;
            }
            antworten(austausch, 200);
        }
    }
    // #endregion anfrage

    private void verarbeiten(JsonNode update) {
        try {
            handler.accept(update);
        } catch (RuntimeException e) {
            // Ein Fehler im Handler ändert nichts mehr an der Antwort an Telegram. Nur den Typ ausgeben.
            System.err.println("Fehler im Handler: " + e.getClass().getSimpleName());
        }
    }

    // #region geheimnis
    /** Zeitkonstanter Vergleich: Die Dauer verrät nicht, wie viele Zeichen des Geheimnisses schon stimmen. */
    static boolean gleich(byte[] erwartet, String erhalten) {
        byte[] gesendet = erhalten == null ? new byte[0] : erhalten.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(erwartet, gesendet);
    }
    // #endregion geheimnis

    private static void antworten(HttpExchange austausch, int status) throws IOException {
        austausch.sendResponseHeaders(status, -1);
    }

    @Override
    public void close() {
        server.stop(0);
        verarbeitung.shutdown();
    }
}
