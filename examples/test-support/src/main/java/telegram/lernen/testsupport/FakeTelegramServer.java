package telegram.lernen.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fake der Telegram Bot API auf dem JDK-{@link HttpServer}, nur für Tests.
 *
 * <p>Die Basis-URL ({@link #baseUrl()}) wird dem Client übergeben, der sonst {@code https://api.telegram.org}
 * nutzt. Der Fake prüft weder Token noch Schema; er zeichnet alle Aufrufe auf und liefert Updates aus einer Queue.
 * Eingebaut sind {@code getMe}, {@code getUpdates} und {@code sendMessage}; weitere Methoden mit
 * {@link #onMethod}, alle anderen antworten mit {@code ok:false} und HTTP 404.
 */
public final class FakeTelegramServer implements AutoCloseable {

    private static final Pattern PFAD = Pattern.compile("^/bot([^/]*)/([A-Za-z]+)$");
    private static final Pattern DATEI_PFAD = Pattern.compile("^/file/bot([^/]*)/(.+)$");
    private static final Pattern OFFSET = Pattern.compile("\"?offset\"?\\s*[:=]\\s*(-?\\d+)");
    private static final Pattern TIMEOUT = Pattern.compile("\"?timeout\"?\\s*[:=]\\s*(\\d+)");
    private static final long MAX_WARTEN_MS = 500;
    private static final int MAX_UPDATES = 100;

    private record Update(long id, String json) {}

    private record Verhalten(long verzoegerungMs, Integer status, String body) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object lock = new Object();

    // Alles Folgende wird nur unter lock gelesen und geschrieben.
    private final List<Update> updates = new ArrayList<>();
    private final List<RecordedRequest> aufrufe = new ArrayList<>();
    private final Map<String, Deque<Verhalten>> verhalten = new HashMap<>();
    private final Map<String, String> ergebnisse = new HashMap<>();
    private final Map<String, byte[]> dateien = new HashMap<>();
    private long naechsteUpdateId = 1;
    private long naechsteNachrichtenId = 100;

    private FakeTelegramServer(HttpServer server) {
        this.server = server;
    }

    public static FakeTelegramServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeTelegramServer fake = new FakeTelegramServer(server);
        server.createContext("/", fake::behandle);
        server.setExecutor(fake.executor);
        server.start();
        return fake;
    }

    /** Basis-URL ohne Schrägstrich am Ende, z. B. {@code http://127.0.0.1:41234}. */
    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    // --- Updates

    /** Legt ein Update mit einer Textnachricht in einem privaten Chat in die Queue. */
    public void enqueueTextMessage(long chatId, String text) {
        long nachrichtenId;
        synchronized (lock) {
            nachrichtenId = naechsteNachrichtenId++;
        }
        enqueueUpdate("message", """
                {"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"Test"},\
                "chat":{"id":%d,"first_name":"Test","type":"private"},"date":1700000000,"text":%s}"""
                .formatted(nachrichtenId, chatId, chatId, jsonText(text)));
    }

    /** Legt ein beliebiges Update in die Queue, z. B. {@code enqueueUpdate("callback_query", "{...}")}. */
    public void enqueueUpdate(String feld, String wertAlsJson) {
        synchronized (lock) {
            long id = naechsteUpdateId++;
            updates.add(new Update(id, "{\"update_id\":" + id + ",\"" + feld + "\":" + wertAlsJson + "}"));
            lock.notifyAll();
        }
    }

    // --- Antworten und Fehlerschalter

    /** Feste Antwort für eine Methode; {@code resultAlsJson} ist der Inhalt von {@code result}. */
    public void onMethod(String methode, String resultAlsJson) {
        synchronized (lock) {
            ergebnisse.put(methode, resultAlsJson);
        }
    }

    /** Der nächste Aufruf der Methode bekommt diese Antwort mit diesem HTTP-Status. */
    public void failNext(String methode, int httpStatus, String bodyAlsJson) {
        queue(methode, new Verhalten(0, httpStatus, bodyAlsJson));
    }

    /** Der nächste Aufruf der Methode bekommt HTTP 429 mit {@code retry_after}. */
    public void rateLimitNext(String methode, int retryAfterSekunden) {
        failNext(methode, 429, """
                {"ok":false,"error_code":429,"description":"Too Many Requests: retry after %d",\
                "parameters":{"retry_after":%d}}""".formatted(retryAfterSekunden, retryAfterSekunden));
    }

    /** Der nächste Aufruf der Methode wird um diese Zeit verzögert, danach normal beantwortet. */
    public void delayNext(String methode, Duration dauer) {
        queue(methode, new Verhalten(dauer.toMillis(), null, null));
    }

    private void queue(String methode, Verhalten v) {
        synchronized (lock) {
            verhalten.computeIfAbsent(methode, k -> new ArrayDeque<>()).add(v);
        }
    }

    /** Stellt eine Datei zum Download unter {@code /file/bot<token>/<pfad>} bereit, wie es getFile ankündigt. */
    public void dateiBereitstellen(String pfad, byte[] inhalt) {
        synchronized (lock) {
            dateien.put(pfad, inhalt.clone());
        }
    }

    // --- Aufzeichnung

    public List<RecordedRequest> requests() {
        synchronized (lock) {
            return List.copyOf(aufrufe);
        }
    }

    public List<RecordedRequest> requests(String methode) {
        return requests().stream().filter(r -> r.method().equals(methode)).toList();
    }

    /** Wartet, bis die Methode mindestens {@code anzahl} Mal aufgerufen wurde. */
    public List<RecordedRequest> awaitRequests(String methode, int anzahl, Duration timeout)
            throws InterruptedException {
        long ende = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (true) {
                List<RecordedRequest> treffer = aufrufe.stream().filter(r -> r.method().equals(methode)).toList();
                if (treffer.size() >= anzahl) {
                    return treffer;
                }
                long restMs = (ende - System.nanoTime()) / 1_000_000;
                if (restMs <= 0) {
                    throw new IllegalStateException("Timeout: " + methode + " wurde " + treffer.size()
                            + " Mal aufgerufen, erwartet " + anzahl);
                }
                lock.wait(restMs);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // --- Server

    private void behandle(HttpExchange ex) throws IOException {
        try (ex) {
            Matcher datei = DATEI_PFAD.matcher(ex.getRequestURI().getPath());
            if (datei.matches()) {
                herunterladen(ex, datei.group(1), datei.group(2));
                return;
            }
            Matcher pfad = PFAD.matcher(ex.getRequestURI().getPath());
            byte[] bytes = ex.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            if (!pfad.matches()) {
                antworte(ex, 404, nichtGefunden());
                return;
            }
            String token = pfad.group(1);
            String methode = pfad.group(2);
            synchronized (lock) {
                aufrufe.add(new RecordedRequest(methode, token, query, body, bytes));
                lock.notifyAll();
            }

            Verhalten v;
            synchronized (lock) {
                Deque<Verhalten> q = verhalten.get(methode);
                v = q == null ? null : q.poll();
            }
            if (v != null) {
                if (v.verzoegerungMs() > 0) {
                    Thread.sleep(v.verzoegerungMs());
                }
                if (v.status() != null) {
                    antworte(ex, v.status(), v.body());
                    return;
                }
            }

            String fest;
            synchronized (lock) {
                fest = ergebnisse.get(methode);
            }
            if (fest != null) {
                antworte(ex, 200, ok(fest));
            } else if (methode.equals("getMe")) {
                antworte(ex, 200, ok("""
                        {"id":1,"is_bot":true,"first_name":"Fake Bot","username":"fake_bot"}"""));
            } else if (methode.equals("getUpdates")) {
                antworte(ex, 200, ok(holeUpdates(body + "&" + query)));
            } else if (methode.equals("sendMessage")) {
                antworte(ex, 200, ok(nachricht(body, query)));
            } else {
                antworte(ex, 404, nichtGefunden());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void herunterladen(HttpExchange ex, String token, String pfad) throws IOException {
        byte[] inhalt;
        synchronized (lock) {
            aufrufe.add(new RecordedRequest("file", token, "", pfad, new byte[0]));
            lock.notifyAll();
            inhalt = dateien.get(pfad);
        }
        if (inhalt == null) {
            antworte(ex, 404, nichtGefunden());
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, inhalt.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(inhalt);
        }
    }

    private String holeUpdates(String parameter) throws InterruptedException {
        Matcher offset = OFFSET.matcher(parameter);
        Matcher timeout = TIMEOUT.matcher(parameter);
        long bestaetigtBis = offset.find() ? Long.parseLong(offset.group(1)) : Long.MIN_VALUE;
        long warteMs = timeout.find() ? Math.min(Long.parseLong(timeout.group(1)) * 1000, MAX_WARTEN_MS) : 0;
        long ende = System.nanoTime() + warteMs * 1_000_000;
        synchronized (lock) {
            // Wie bei Telegram: ein Update gilt als bestätigt, sobald offset > update_id.
            updates.removeIf(u -> u.id() < bestaetigtBis);
            while (updates.isEmpty()) {
                long restMs = (ende - System.nanoTime()) / 1_000_000;
                if (restMs <= 0) {
                    break;
                }
                lock.wait(restMs);
                updates.removeIf(u -> u.id() < bestaetigtBis);
            }
            return "[" + String.join(",", updates.stream().limit(MAX_UPDATES).map(Update::json).toList()) + "]";
        }
    }

    private String nachricht(String body, String query) {
        String chatId = zahl(body, query, "chat_id");
        String text = text(body, query);
        long id;
        synchronized (lock) {
            id = naechsteNachrichtenId++;
        }
        return "{\"message_id\":%d,\"from\":{\"id\":1,\"is_bot\":true,\"first_name\":\"Fake Bot\",\"username\":\"fake_bot\"},\"chat\":{\"id\":%s,\"type\":\"private\"},\"date\":1700000000,\"text\":%s}"
                .formatted(id, chatId, text);
    }

    // --- Hilfsfunktionen

    private static String zahl(String body, String query, String name) {
        Matcher json = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(body);
        if (json.find()) {
            return json.group(1);
        }
        String formular = formularWert(body + "&" + query, name);
        return formular != null && formular.matches("-?\\d+") ? formular : "0";
    }

    /** Liefert den Text als JSON-Literal (mit Anführungszeichen). */
    private static String text(String body, String query) {
        String literal = jsonLiteral(body, "text");
        if (literal != null) {
            return literal;
        }
        String formular = formularWert(body + "&" + query, "text");
        return jsonText(formular == null ? "" : formular);
    }

    /**
     * Sucht {@code "schluessel": "..."} und gibt den String samt Anführungszeichen zurück. Mit einer Schleife statt
     * einer Regex, weil {@code (?:[^"\\]|\\.)*} bei langen Texten den Stack überläuft.
     */
    static String jsonLiteral(String json, String schluessel) {
        Matcher kopf = Pattern.compile("\"" + Pattern.quote(schluessel) + "\"\\s*:\\s*\"").matcher(json);
        if (!kopf.find()) {
            return null;
        }
        int start = kopf.end() - 1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return json.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String formularWert(String formular, String name) {
        for (String paar : formular.split("&")) {
            int gleich = paar.indexOf('=');
            if (gleich > 0 && paar.substring(0, gleich).equals(name)) {
                return URLDecoder.decode(paar.substring(gleich + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String jsonText(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String ok(String resultAlsJson) {
        return "{\"ok\":true,\"result\":" + resultAlsJson + "}";
    }

    private static String nichtGefunden() {
        return "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}";
    }

    private static void antworte(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
