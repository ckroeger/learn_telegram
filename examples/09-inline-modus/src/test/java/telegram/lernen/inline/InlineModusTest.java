package telegram.lernen.inline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class InlineModusTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeTelegramServer fake;
    private TelegramClient client;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("answerInlineQuery", "true");
        client = new TelegramClient("123456:GEHEIM", fake.baseUrl());
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode abfrage(String text) throws Exception {
        return JSON.readTree("{\"update_id\":1,\"inline_query\":{\"id\":\"iq1\",\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"query\":" + JSON.writeValueAsString(text) + ",\"offset\":\"\"}}");
    }

    private JsonNode letzteAntwort() throws Exception {
        return JSON.readTree(fake.requests("answerInlineQuery").getLast().body());
    }

    // --- InlineEcho

    @Test
    void echoAntwortetMitEinemArtikelMitDemText() throws Exception {
        new InlineEcho(client).behandle(abfrage("  Hallo Welt "));

        JsonNode antwort = letzteAntwort();
        assertEquals("iq1", antwort.get("inline_query_id").asText());
        assertEquals(1, antwort.get("results").size());
        assertEquals("Senden: Hallo Welt", antwort.at("/results/0/title").asText());
        assertEquals("Hallo Welt", antwort.at("/results/0/input_message_content/message_text").asText());
        assertEquals(5, antwort.get("cache_time").asInt());
    }

    @Test
    void echoAntwortetAufEineLeereAbfrageMitEinerLeerenListe() throws Exception {
        new InlineEcho(client).behandle(abfrage("   "));

        assertEquals(0, letzteAntwort().get("results").size());
    }

    @Test
    void echoIgnoriertAndereUpdates() throws Exception {
        new InlineEcho(client).behandle(JSON.readTree("{\"update_id\":2,\"message\":{\"message_id\":1}}"));

        assertEquals(0, fake.requests("answerInlineQuery").size());
    }

    // --- Übung: Rechner

    @Test
    void rechnerRechnetDieVierGrundrechenarten() {
        assertEquals(Optional.of("12 + 30 = 42"), UebungLoesung.rechnen("12+30"));
        assertEquals(Optional.of("5 - 8 = -3"), UebungLoesung.rechnen(" 5 -8 "));
        assertEquals(Optional.of("6 * 7 = 42"), UebungLoesung.rechnen("6*7"));
        assertEquals(Optional.of("1 / 4 = 0.25"), UebungLoesung.rechnen("1/4"));
    }

    @Test
    void rechnerRundetNichtMitFliesskommaFehlern() {
        assertEquals(Optional.of("0.1 + 0.2 = 0.3"), UebungLoesung.rechnen("0.1+0.2"));
        assertEquals(Optional.of("1.5 * 2 = 3"), UebungLoesung.rechnen("1.5*2"));
        assertEquals(Optional.of("1 - 1 = 0"), UebungLoesung.rechnen("1-1"));
    }

    @Test
    void rechnerKenntNegativeZahlen() {
        assertEquals(Optional.of("-2 * -3 = 6"), UebungLoesung.rechnen("-2 * -3"));
    }

    @Test
    void rechnerLiefertNichtsBeiMuellOderDivisionDurchNull() {
        for (String eingabe : new String[] {"", "abc", "12+", "1/0", "1/0.0", "1+2+3", "1e5+1", "1".repeat(16) + "+1"}) {
            assertTrue(UebungLoesung.rechnen(eingabe).isEmpty(), eingabe);
        }
    }

    @Test
    void rechnerAntwortetMitDemErgebnisAlsArtikel() throws Exception {
        UebungLoesung.antworten(client, abfrage("12+30").get("inline_query"));

        assertEquals("12 + 30 = 42", letzteAntwort().at("/results/0/input_message_content/message_text").asText());
    }

    @Test
    void rechnerAntwortetBeiEinerUngueltigenAufgabeMitEinerLeerenListe() throws Exception {
        UebungLoesung.antworten(client, abfrage("hallo").get("inline_query"));

        assertEquals(0, letzteAntwort().get("results").size());
    }
}
