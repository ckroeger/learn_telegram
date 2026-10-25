package telegram.lernen.miniapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import telegram.lernen.client.Inline;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;

/**
 * Der Server hinter der Mini App: liefert die statischen Dateien aus und hat zwei Endpunkte.
 * {@code /verify} prüft die {@code initData}, {@code /antworten} sendet nach der Prüfung eine Nachricht im Namen
 * des Nutzers in seinen Chat. Er lauscht nur auf dem eigenen Rechner. HTTPS liefert ein Tunnel oder ein Proxy davor.
 */
public final class MiniAppServer implements AutoCloseable {

    /** Eine Anfrage mit initData und kurzem Text ist klein. Alles darüber wird nicht gelesen. */
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_TEXT = 200;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String[]> DATEIEN = Map.of(
            "/", new String[] {"index.html", "text/html; charset=utf-8"},
            "/index.html", new String[] {"index.html", "text/html; charset=utf-8"},
            "/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"},
            "/style.css", new String[] {"style.css", "text/css; charset=utf-8"});

    private final HttpServer server;
    private final InitDataPruefer pruefer;
    private final TelegramClient client;
    private final Clock uhr;
    private final Map<String, byte[]> inhalte;

    private MiniAppServer(HttpServer server, InitDataPruefer pruefer, TelegramClient client, Clock uhr) {
        this.server = server;
        this.pruefer = pruefer;
        this.client = client;
        this.uhr = uhr;
        this.inhalte = Map.copyOf(dateienLaden());
    }

    /** Startet den Server. Port 0 wählt einen freien Port. */
    public static MiniAppServer starten(int port, InitDataPruefer pruefer, TelegramClient client, Clock uhr)
            throws IOException {
        // Nur Loopback: erreichbar ist der Server von außen allein über den Tunnel oder Proxy.
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        MiniAppServer mini = new MiniAppServer(server, pruefer, client, uhr);
        server.createContext("/", mini::anfrage);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return mini;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Die Adresse, an die der Server gebunden ist. */
    InetAddress adresse() {
        return server.getAddress().getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Map<String, byte[]> dateienLaden() {
        Map<String, byte[]> geladen = new java.util.HashMap<>();
        for (String[] datei : DATEIEN.values()) {
            try (InputStream eingang = MiniAppServer.class.getResourceAsStream("/web/" + datei[0])) {
                if (eingang == null) {
                    throw new IllegalStateException("Datei fehlt im Klassenpfad: /web/" + datei[0]);
                }
                geladen.put(datei[0], eingang.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return geladen;
    }

    // #region verteiler
    private void anfrage(HttpExchange austausch) throws IOException {
        try (austausch) {
            String pfad = austausch.getRequestURI().getPath();
            String methode = austausch.getRequestMethod();
            String[] datei = DATEIEN.get(pfad);
            if (datei != null) {
                if (!methode.equals("GET")) {
                    senden(austausch, 405, "text/plain; charset=utf-8", "Nur GET".getBytes(StandardCharsets.UTF_8), "GET");
                } else {
                    senden(austausch, 200, datei[1], inhalte.get(datei[0]), null);
                }
            } else if (pfad.equals("/verify") || pfad.equals("/antworten")) {
                if (!methode.equals("POST")) {
                    senden(austausch, 405, "text/plain; charset=utf-8", "Nur POST".getBytes(StandardCharsets.UTF_8), "POST");
                } else if (pfad.equals("/verify")) {
                    verify(austausch);
                } else {
                    antworten(austausch);
                }
            } else {
                senden(austausch, 404, "text/plain; charset=utf-8", "Nicht gefunden".getBytes(StandardCharsets.UTF_8), null);
            }
        } catch (RuntimeException e) {
            // Ein Fehler in einer Anfrage darf den Server nicht beenden. Nur der Typ wird ausgegeben.
            System.err.println("Fehler bei einer Anfrage: " + e.getClass().getSimpleName());
        }
    }
    // #endregion verteiler

    // #region verify
    private void verify(HttpExchange austausch) throws IOException {
        JsonNode anfrage = jsonLesen(austausch);
        if (anfrage == null) {
            return;
        }
        GepruefteInitData daten = pruefen(austausch, anfrage);
        if (daten == null) {
            return;
        }
        ObjectNode antwort = JSON.createObjectNode().put("ok", true).put("id", daten.userId())
                .put("vorname", daten.vorname()).put("kannAntworten", daten.kannAntworten());
        antwort.put("username", daten.username());
        json(austausch, 200, antwort);
    }

    /** Prüft {@code initData} aus der Anfrage. Bei einem Fehler ist die Antwort schon gesendet, das Ergebnis {@code null}. */
    private GepruefteInitData pruefen(HttpExchange austausch, JsonNode anfrage) throws IOException {
        JsonNode initData = anfrage.get("initData");
        if (initData == null || !initData.isTextual()) {
            fehler(austausch, 400, "Das Feld initData fehlt");
            return null;
        }
        try {
            return pruefer.pruefen(initData.asText(), uhr.instant());
        } catch (UngueltigeInitData e) {
            fehler(austausch, 401, e.getMessage());
            return null;
        }
    }
    // #endregion verify

    // #region antworten
    private void antworten(HttpExchange austausch) throws IOException {
        JsonNode anfrage = jsonLesen(austausch);
        if (anfrage == null) {
            return;
        }
        GepruefteInitData daten = pruefen(austausch, anfrage);
        if (daten == null) {
            return;
        }
        // Die Abfrage-ID kommt aus den geprüften Daten, nie aus einem eigenen Feld der Anfrage.
        if (!daten.kannAntworten()) {
            fehler(austausch, 400, "Diese Mini App wurde nicht über die Menütaste oder eine Inline-Taste geöffnet");
            return;
        }
        JsonNode textFeld = anfrage.get("text");
        String text = textFeld != null && textFeld.isTextual() ? textFeld.asText().strip() : "";
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            fehler(austausch, 400, "Der Text braucht 1 bis " + MAX_TEXT + " Zeichen");
            return;
        }
        ObjectNode parameter = TelegramClient.parameter().put("web_app_query_id", daten.queryId());
        // Der Text stammt vom Nutzer. Er wird als normaler Text gesendet (kein parse_mode), also ohne Formatierung.
        parameter.set("result", Inline.artikel("gruss-" + UUID.randomUUID(), "Gruß aus der Mini App", null,
                daten.vorname() + " grüßt aus der Mini App: " + text));
        try {
            client.aufrufen("answerWebAppQuery", parameter);
        } catch (TelegramException e) {
            System.err.println("answerWebAppQuery abgelehnt: " + e.getMessage());
            fehler(austausch, 502, "Telegram hat die Nachricht abgelehnt");
            return;
        } catch (IOException e) {
            System.err.println("answerWebAppQuery nicht erreichbar (" + e.getClass().getSimpleName() + ")");
            fehler(austausch, 502, "Telegram ist nicht erreichbar");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fehler(austausch, 503, "Der Server wird beendet");
            return;
        }
        json(austausch, 200, JSON.createObjectNode().put("ok", true));
    }
    // #endregion antworten

    /** Liest den Körper als JSON-Objekt. Bei einem Fehler ist die Antwort schon gesendet, das Ergebnis {@code null}. */
    private static JsonNode jsonLesen(HttpExchange austausch) throws IOException {
        byte[] koerper;
        try (InputStream eingang = austausch.getRequestBody()) {
            koerper = eingang.readNBytes(MAX_BYTES + 1);
        }
        if (koerper.length > MAX_BYTES) {
            fehler(austausch, 413, "Die Anfrage ist zu groß");
            return null;
        }
        try {
            JsonNode json = JSON.readTree(koerper);
            if (json == null || !json.isObject()) {
                fehler(austausch, 400, "Erwartet wird ein JSON-Objekt");
                return null;
            }
            return json;
        } catch (JsonProcessingException e) {
            fehler(austausch, 400, "Kein gültiges JSON");
            return null;
        }
    }

    private static void fehler(HttpExchange austausch, int status, String meldung) throws IOException {
        json(austausch, status, JSON.createObjectNode().put("ok", false).put("fehler", meldung));
    }

    private static void json(HttpExchange austausch, int status, ObjectNode inhalt) throws IOException {
        senden(austausch, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(inhalt), null);
    }

    private static void senden(HttpExchange austausch, int status, String typ, byte[] inhalt, String erlaubt)
            throws IOException {
        austausch.getResponseHeaders().set("Content-Type", typ);
        austausch.getResponseHeaders().set("Cache-Control", "no-store");
        austausch.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (erlaubt != null) {
            austausch.getResponseHeaders().set("Allow", erlaubt);
        }
        austausch.sendResponseHeaders(status, inhalt.length);
        austausch.getResponseBody().write(inhalt);
    }
}
