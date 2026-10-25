package telegram.lernen.miniapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class MiniAppServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = InitDataPrueferTest.TOKEN;
    private static final Instant JETZT = Instant.ofEpochSecond(1_700_000_600);

    private FakeTelegramServer fake;
    private MiniAppServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("answerWebAppQuery", "{\"inline_message_id\":\"m1\"}");
        server = MiniAppServer.starten(0, new InitDataPruefer(TOKEN, Duration.ofHours(1)),
                new TelegramClient(TOKEN, fake.baseUrl()), Clock.fixed(JETZT, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.close();
        fake.close();
    }

    private HttpResponse<String> get(String pfad) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pfad)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String pfad, String koerper) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pfad))
                .POST(HttpRequest.BodyPublishers.ofString(koerper)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String anfrage(String initData, String text) throws Exception {
        Map<String, Object> felder = new LinkedHashMap<>();
        felder.put("initData", initData);
        if (text != null) {
            felder.put("text", text);
        }
        return JSON.writeValueAsString(felder);
    }

    private static String initDataMitUser(String queryId) throws Exception {
        Map<String, String> felder = new LinkedHashMap<>();
        felder.put("auth_date", "1700000500");
        felder.put("user", "{\"id\":42,\"first_name\":\"Anna\"}");
        if (queryId != null) {
            felder.put("query_id", queryId);
        }
        return InitDataPrueferTest.signiert(TOKEN, felder);
    }

    // --- statische Dateien

    @Test
    void liefertSeiteSkriptUndStil() throws Exception {
        HttpResponse<String> seite = get("/");
        assertEquals(200, seite.statusCode());
        assertTrue(seite.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(seite.body().contains("telegram-web-app.js"));
        assertEquals(seite.body(), get("/index.html").body());
        assertTrue(get("/app.js").headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertTrue(get("/app.js").body().contains("/verify"));
        assertTrue(get("/style.css").headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
    }

    @Test
    void antwortenHabenSicherheitsKopfzeilenUndKeinenCache() throws Exception {
        HttpResponse<String> seite = get("/");

        assertEquals("nosniff", seite.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-store", seite.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void unbekannteUndGefaehrlichePfadeSind404() throws Exception {
        for (String pfad : new String[] {"/nope", "/web/index.html", "/../pom.xml", "/%2e%2e/pom.xml", "/index.html/", "/app.js%00"}) {
            assertEquals(404, get(pfad).statusCode(), pfad);
        }
    }

    @Test
    void falscheMethodenSind405MitAllowKopf() throws Exception {
        assertEquals(405, post("/", "{}").statusCode());
        HttpResponse<String> getVerify = get("/verify");
        assertEquals(405, getVerify.statusCode());
        assertEquals("POST", getVerify.headers().firstValue("Allow").orElse(""));
        assertEquals(405, get("/antworten").statusCode());
    }

    // --- /verify

    @Test
    void verifyBestaetigtEchteDatenUndNenntDenNutzer() throws Exception {
        HttpResponse<String> antwort = post("/verify", anfrage(initDataMitUser("Q1"), null));

        assertEquals(200, antwort.statusCode());
        JsonNode json = JSON.readTree(antwort.body());
        assertTrue(json.get("ok").asBoolean());
        assertEquals(42, json.get("id").asLong());
        assertEquals("Anna", json.get("vorname").asText());
        assertTrue(json.get("username").isNull());
        assertTrue(json.get("kannAntworten").asBoolean());
        assertFalse(antwort.body().contains("Q1"), "die Abfrage-ID gehört dem Server");
    }

    @Test
    void verifyOhneQueryIdMeldetKannAntwortenFalse() throws Exception {
        JsonNode json = JSON.readTree(post("/verify", anfrage(initDataMitUser(null), null)).body());

        assertFalse(json.get("kannAntworten").asBoolean());
    }

    @Test
    void verifyLehntGefaelschteDatenMit401Ab() throws Exception {
        HttpResponse<String> antwort = post("/verify", anfrage(initDataMitUser("Q1").replace("Anna", "Eve"), null));

        assertEquals(401, antwort.statusCode());
        JsonNode json = JSON.readTree(antwort.body());
        assertFalse(json.get("ok").asBoolean());
        assertTrue(json.get("fehler").asText().contains("Signatur"));
    }

    @Test
    void verifyLehntDatenEinesAnderenBotsAb() throws Exception {
        String fremd = InitDataPrueferTest.signiert("999:ANDERER", Map.of("auth_date", "1700000500", "user", "{\"id\":1}"));

        assertEquals(401, post("/verify", anfrage(fremd, null)).statusCode());
    }

    @Test
    void verifyLehntKaputteAnfragenAb() throws Exception {
        assertEquals(400, post("/verify", "kein json").statusCode());
        assertEquals(400, post("/verify", "[1]").statusCode());
        assertEquals(400, post("/verify", "").statusCode());
        assertEquals(400, post("/verify", "{}").statusCode());
        assertEquals(400, post("/verify", "{\"initData\":5}").statusCode());
        assertEquals(401, post("/verify", "{\"initData\":\"\"}").statusCode());
    }

    @Test
    void zuGrosseAnfragenSind413() throws Exception {
        assertEquals(413, post("/verify", "{\"initData\":\"" + "x".repeat(MiniAppServer.MAX_BYTES) + "\"}").statusCode());
    }

    // --- /antworten

    @Test
    void antwortenSendetAnswerWebAppQueryMitDerGepruftenQueryId() throws Exception {
        HttpResponse<String> antwort = post("/antworten", anfrage(initDataMitUser("Q1"), "  Hallo Welt "));

        assertEquals(200, antwort.statusCode());
        assertTrue(JSON.readTree(antwort.body()).get("ok").asBoolean());
        JsonNode gesendet = JSON.readTree(fake.requests("answerWebAppQuery").getLast().body());
        assertEquals("Q1", gesendet.get("web_app_query_id").asText());
        assertEquals("article", gesendet.at("/result/type").asText());
        assertEquals("Anna grüßt aus der Mini App: Hallo Welt", gesendet.at("/result/input_message_content/message_text").asText());
        assertFalse(gesendet.at("/result/input_message_content").has("parse_mode"));
        assertTrue(gesendet.at("/result/id").asText().length() <= 64);
    }

    @Test
    void antwortenIgnoriertEineQueryIdImKoerperDerAnfrage() throws Exception {
        String koerper = "{\"initData\":" + JSON.writeValueAsString(initDataMitUser("ECHT"))
                + ",\"text\":\"x\",\"query_id\":\"GEFAELSCHT\",\"web_app_query_id\":\"GEFAELSCHT\"}";

        post("/antworten", koerper);

        assertEquals("ECHT", JSON.readTree(fake.requests("answerWebAppQuery").getLast().body()).get("web_app_query_id").asText());
    }

    @Test
    void antwortenBrauchtGepruefteDatenUndRuftTelegramSonstNichtAuf() throws Exception {
        assertEquals(401, post("/antworten", anfrage(initDataMitUser("Q1").replace("Anna", "Eve"), "x")).statusCode());
        assertEquals(401, post("/antworten", anfrage("auth_date=1&hash=00", "x")).statusCode());
        assertEquals(0, fake.requests("answerWebAppQuery").size());
    }

    @Test
    void antwortenOhneQueryIdIst400() throws Exception {
        HttpResponse<String> antwort = post("/antworten", anfrage(initDataMitUser(null), "x"));

        assertEquals(400, antwort.statusCode());
        assertTrue(antwort.body().contains("Menütaste"));
        assertEquals(0, fake.requests("answerWebAppQuery").size());
    }

    @Test
    void antwortenPrueftDenText() throws Exception {
        String daten = initDataMitUser("Q1");

        assertEquals(400, post("/antworten", anfrage(daten, null)).statusCode());
        assertEquals(400, post("/antworten", anfrage(daten, "   ")).statusCode());
        assertEquals(400, post("/antworten", anfrage(daten, "x".repeat(MiniAppServer.MAX_TEXT + 1))).statusCode());
        assertEquals(400, post("/antworten", "{\"initData\":" + JSON.writeValueAsString(daten) + ",\"text\":7}").statusCode());
        assertEquals(200, post("/antworten", anfrage(daten, "x".repeat(MiniAppServer.MAX_TEXT))).statusCode());
        assertEquals(1, fake.requests("answerWebAppQuery").size());
    }

    @Test
    void textMitHtmlOderSteuerzeichenGehtUnveraendertAlsText() throws Exception {
        post("/antworten", anfrage(initDataMitUser("Q1"), "<b>fett</b> & \"zitat\"\nzweite Zeile"));

        JsonNode gesendet = JSON.readTree(fake.requests("answerWebAppQuery").getLast().body());
        assertEquals("Anna grüßt aus der Mini App: <b>fett</b> & \"zitat\"\nzweite Zeile",
                gesendet.at("/result/input_message_content/message_text").asText());
    }

    @Test
    void lehntTelegramAbIst502OhneDetailsFuerDenClient() throws Exception {
        fake.failNext("answerWebAppQuery", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: query is too old\"}");

        HttpResponse<String> antwort = post("/antworten", anfrage(initDataMitUser("Q1"), "x"));

        assertEquals(502, antwort.statusCode());
        assertFalse(antwort.body().contains("too old"));
        assertFalse(JSON.readTree(antwort.body()).get("ok").asBoolean());
        assertEquals(200, post("/antworten", anfrage(initDataMitUser("Q2"), "x")).statusCode(), "Server läuft weiter");
    }

    @Test
    void keineAntwortEnthaeltDenToken() throws Exception {
        String daten = initDataMitUser("Q1");
        String[] antworten = {get("/").body(), get("/app.js").body(), post("/verify", anfrage(daten, null)).body(),
                post("/verify", "{").body(), post("/antworten", anfrage(daten, "x")).body(), get("/nope").body()};

        for (String antwort : antworten) {
            assertFalse(antwort.contains(TOKEN) || antwort.contains("AAH-nur-fuer-tests"));
        }
    }

    @Test
    void lauschtNurAufDemLoopbackInterface() {
        assertTrue(server.adresse().isLoopbackAddress());
    }
}
