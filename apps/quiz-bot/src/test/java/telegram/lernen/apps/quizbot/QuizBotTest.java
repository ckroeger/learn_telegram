package telegram.lernen.apps.quizbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class QuizBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CHAT = 42;

    private static final List<Frage> FRAGEN = List.of(
            new Frage("Eins?", List.of("a", "b", "c"), 1, "weil b"),
            new Frage("Zwei?", List.of("x", "y"), 0, "weil x"),
            new Frage("Drei?", List.of("p", "q"), 1, "weil q"));

    private FakeTelegramServer fake;
    private QuizBot bot;
    private int pollZaehler;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("answerCallbackQuery", "true");
        bot = new QuizBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", FRAGEN);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    // --- Hilfen: Updates wie von Telegram

    private static JsonNode nachricht(long chatId, String text) throws Exception {
        int ende = text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ');
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"},\"date\":1,\"text\":\""
                + text + "\",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + ende + "}]}}");
    }

    private static JsonNode klick(long chatId, String daten) throws Exception {
        return JSON.readTree("{\"update_id\":2,\"callback_query\":{\"id\":\"q1\",\"from\":{\"id\":7,\"is_bot\":false,"
                + "\"first_name\":\"T\"},\"chat_instance\":\"c\",\"data\":\"" + daten + "\",\"message\":{\"message_id\":9,"
                + "\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"},\"date\":1}}}");
    }

    private static JsonNode stimme(String pollId, long nutzer, String name, String optionen) throws Exception {
        return JSON.readTree("{\"update_id\":3,\"poll_answer\":{\"poll_id\":\"" + pollId + "\",\"user\":{\"id\":" + nutzer
                + ",\"is_bot\":false,\"first_name\":\"" + name + "\"},\"option_ids\":" + optionen + "}}");
    }

    /** Antwort von sendPoll: eine Nachricht mit der Umfrage, deren ID wir in den Tests wiederfinden. */
    private void pollAntwortVorbereiten(int anzahl) {
        for (int i = 1; i <= anzahl; i++) {
            fake.onMethod("sendPoll", "{\"message_id\":" + (100 + i) + ",\"chat\":{\"id\":" + CHAT + ",\"type\":\"private\"},"
                    + "\"date\":1,\"poll\":{\"id\":\"poll" + i + "\",\"question\":\"x\",\"options\":[],\"is_anonymous\":false,"
                    + "\"type\":\"quiz\",\"total_voter_count\":0,\"is_closed\":false}}");
        }
    }

    /** Der Fake liefert immer dieselbe Umfrage zurück, darum bekommt jede Frage in den Tests eine eigene ID. */
    private String frageStellenMitId(int nummer) throws Exception {
        String id = "poll" + (++pollZaehler);
        fake.onMethod("sendPoll", "{\"message_id\":" + (100 + pollZaehler) + ",\"chat\":{\"id\":" + CHAT
                + ",\"type\":\"private\"},\"date\":1,\"poll\":{\"id\":\"" + id + "\",\"question\":\"x\",\"options\":[],"
                + "\"is_anonymous\":false,\"type\":\"quiz\",\"total_voter_count\":0,\"is_closed\":false}}");
        if (nummer == 0) {
            bot.behandle(nachricht(CHAT, "/quiz"));
        } else {
            bot.behandle(klick(CHAT, "weiter:" + nummer));
        }
        return id;
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    private String letzterText() throws Exception {
        return letzte("sendMessage").get("text").asText();
    }

    // --- Frage senden

    @Test
    void quizStartetMitDerErstenFrageAlsQuizUmfrage() throws Exception {
        frageStellenMitId(0);

        JsonNode umfrage = letzte("sendPoll");
        assertEquals(CHAT, umfrage.get("chat_id").asLong());
        assertEquals("(1/3) Eins?", umfrage.get("question").asText());
        assertEquals("quiz", umfrage.get("type").asText());
        assertFalse(umfrage.get("is_anonymous").asBoolean(), "sonst gibt es keine poll_answer-Updates");
        assertEquals(1, umfrage.get("correct_option_ids").get(0).asInt());
        assertEquals(1, umfrage.get("correct_option_ids").size());
        assertEquals("weil b", umfrage.get("explanation").asText());
        assertEquals(3, umfrage.get("options").size());
        assertEquals("b", umfrage.get("options").get(1).get("text").asText());
    }

    @Test
    void umfrageTraegtDieTasteFuerDieNaechsteFrage() throws Exception {
        frageStellenMitId(0);

        JsonNode taste = letzte("sendPoll").at("/reply_markup/inline_keyboard/0/0");
        assertEquals("Nächste Frage", taste.get("text").asText());
        assertEquals("weiter:1", taste.get("callback_data").asText());
    }

    @Test
    void dieLetzteFrageHatDieTasteFuerDasErgebnis() throws Exception {
        frageStellenMitId(0);
        frageStellenMitId(1);
        frageStellenMitId(2);

        JsonNode taste = letzte("sendPoll").at("/reply_markup/inline_keyboard/0/0");
        assertEquals("Ergebnis anzeigen", taste.get("text").asText());
        assertEquals("ergebnis", taste.get("callback_data").asText());
    }

    @Test
    void nachstesFrageStelltDieNaechsteFrageUndBeantwortetDenKlick() throws Exception {
        frageStellenMitId(0);
        frageStellenMitId(1);

        assertEquals("(2/3) Zwei?", letzte("sendPoll").get("question").asText());
        assertEquals("q1", letzte("answerCallbackQuery").get("callback_query_id").asText());
    }

    @Test
    void doppelterKlickStelltDieFrageNurEinmal() throws Exception {
        frageStellenMitId(0);
        frageStellenMitId(1);
        bot.behandle(klick(CHAT, "weiter:1"));

        assertEquals(2, fake.requests("sendPoll").size());
        assertTrue(letzte("answerCallbackQuery").get("text").asText().contains("schon gestellt"));
    }

    @Test
    void ueberspringenDerReihenfolgeIstNichtMoeglich() throws Exception {
        frageStellenMitId(0);
        bot.behandle(klick(CHAT, "weiter:2"));

        assertEquals(1, fake.requests("sendPoll").size());
        assertEquals(1, fake.requests("answerCallbackQuery").size());
    }

    @Test
    void weiterOhneLaufendeRundeOderMitMuellIstVeraltet() throws Exception {
        bot.behandle(klick(CHAT, "weiter:1"));
        bot.behandle(klick(CHAT, "weiter:abc"));
        bot.behandle(klick(CHAT, "weiter:99"));
        bot.behandle(klick(CHAT, "gibt-es-nicht"));

        assertEquals(0, fake.requests("sendPoll").size());
        assertEquals(4, fake.requests("answerCallbackQuery").size(), "jeder Klick wird beantwortet");
    }

    @Test
    void neuerQuizBefehlBeginntEineNeueRunde() throws Exception {
        frageStellenMitId(0);
        frageStellenMitId(1);
        frageStellenMitId(0);
        frageStellenMitId(1);

        assertEquals(4, fake.requests("sendPoll").size());
    }

    @Test
    void rundenSindProChatGetrennt() throws Exception {
        frageStellenMitId(0);
        bot.behandle(klick(99, "weiter:1"));

        assertEquals(1, fake.requests("sendPoll").size(), "Chat 99 hat keine Runde");
    }

    // --- Antworten und Punkte

    @Test
    void richtigeAntwortGibtEinenPunkt() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[1]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertEquals("Bestenliste\n1. Anna: 1 von 1 richtig", letzterText());
    }

    @Test
    void falscheAntwortZaehltAlsVersuchOhnePunkt() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[0]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertEquals("Bestenliste\n1. Anna: 0 von 1 richtig", letzterText());
    }

    @Test
    void mehrereAntwortenAufEineEinzelfrageSindFalsch() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[1,2]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertTrue(letzterText().contains("0 von 1"));
    }

    @Test
    void dieselbeAntwortZaehltNurEinmal() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[1]"));
        bot.behandle(stimme(poll, 7, "Anna", "[1]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertTrue(letzterText().contains("1 von 1"));
    }

    @Test
    void punkteSammelnSichUeberMehrereFragen() throws Exception {
        String eins = frageStellenMitId(0);
        String zwei = frageStellenMitId(1);
        bot.behandle(stimme(eins, 7, "Anna", "[1]"));
        bot.behandle(stimme(zwei, 7, "Anna", "[0]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertTrue(letzterText().contains("Anna: 2 von 2 richtig"));
    }

    @Test
    void zurueckgezogeneStimmeUnbekanntePollUndAnonymeAbsenderWerdenIgnoriert() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[]"));
        bot.behandle(stimme("fremde-poll", 7, "Anna", "[1]"));
        bot.behandle(JSON.readTree("{\"update_id\":3,\"poll_answer\":{\"poll_id\":\"" + poll
                + "\",\"voter_chat\":{\"id\":-5,\"type\":\"supergroup\"},\"option_ids\":[1]}}"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertTrue(letzterText().startsWith("Noch keine Antworten"));
    }

    @Test
    void punkteGehoerenZumChatDerFrage() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[1]"));
        bot.behandle(nachricht(99, "/rangliste"));

        assertTrue(letzterText().startsWith("Noch keine Antworten"), "anderer Chat, eigene Liste");
    }

    // --- Bestenliste

    @Test
    void bestenlisteSortiertNachPunktenDannVersuchenDannName() throws Exception {
        String eins = frageStellenMitId(0);
        String zwei = frageStellenMitId(1);
        bot.behandle(stimme(eins, 1, "Zoe", "[1]"));
        bot.behandle(stimme(zwei, 1, "Zoe", "[0]"));
        bot.behandle(stimme(eins, 2, "Ben", "[1]"));
        bot.behandle(stimme(eins, 3, "Cem", "[0]"));
        bot.behandle(stimme(zwei, 3, "Cem", "[0]"));
        bot.behandle(stimme(eins, 4, "Abi", "[1]"));
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertEquals("Bestenliste\n1. Zoe: 2 von 2 richtig\n2. Abi: 1 von 1 richtig\n3. Ben: 1 von 1 richtig\n"
                + "4. Cem: 1 von 2 richtig", letzterText());
    }

    @Test
    void bestenlisteZeigtHoechstensZehnPlaetze() throws Exception {
        String poll = frageStellenMitId(0);
        for (int i = 1; i <= 12; i++) {
            bot.behandle(stimme(poll, i, "N" + String.format("%02d", i), "[1]"));
        }
        bot.behandle(nachricht(CHAT, "/rangliste"));

        assertEquals(11, letzterText().split("\n").length, "Überschrift und zehn Plätze");
    }

    @Test
    void ergebnisTasteZeigtDieBestenliste() throws Exception {
        String poll = frageStellenMitId(0);
        bot.behandle(stimme(poll, 7, "Anna", "[1]"));
        bot.behandle(klick(CHAT, "ergebnis"));

        assertTrue(letzterText().contains("1. Anna"));
        assertEquals("q1", letzte("answerCallbackQuery").get("callback_query_id").asText());
    }

    // --- Befehle

    @Test
    void hilfeUndStartNennenDieBefehle() throws Exception {
        bot.behandle(nachricht(CHAT, "/start"));
        assertTrue(letzterText().contains("/quiz") && letzterText().contains("/rangliste"));
        bot.behandle(nachricht(CHAT, "/hilfe"));
        assertTrue(letzterText().contains("/quiz"));
    }

    @Test
    void befehlAnEinenAnderenBotUndUnbekannterBefehl() throws Exception {
        bot.behandle(nachricht(CHAT, "/quiz@anderer_bot"));
        assertEquals(0, fake.requests().stream().filter(r -> !r.method().equals("getUpdates")).count());

        bot.behandle(nachricht(CHAT, "/gibtsnicht"));
        assertTrue(letzterText().contains("/hilfe"));
    }

    @Test
    void normalerTextOhneBefehlWirdIgnoriert() throws Exception {
        bot.behandle(JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":5,\"chat\":{\"id\":42,\"type\":\"private\"},"
                + "\"date\":1,\"text\":\"hallo\"}}"));

        assertEquals(0, fake.requests("sendMessage").size());
    }

    // --- Fragen prüfen

    @Test
    void mitgelieferteFragenHaltenDieGrenzenVonSendPollEin() {
        assertTrue(Fragen.STANDARD.size() >= 3);
        long unterschiedlicheRichtige = Fragen.STANDARD.stream().map(Frage::richtig).distinct().count();
        assertTrue(unterschiedlicheRichtige > 1, "die richtige Antwort steht nicht immer an derselben Stelle");
    }

    @Test
    void ungueltigeFragenWerdenAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> new Frage("", List.of("a", "b"), 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new Frage("x".repeat(301), List.of("a", "b"), 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new Frage("f", List.of("a"), 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new Frage("f", List.of("a", "b"), 2, ""));
        assertThrows(IllegalArgumentException.class, () -> new Frage("f", List.of("a", "x".repeat(101)), 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new Frage("f", List.of("a", "b"), 0, "e".repeat(201)));
        assertThrows(IllegalArgumentException.class, () -> new QuizBot(null, "b", List.of()));
    }

    // --- Mit echter Poller-Schleife

    @Test
    void ganzerLaufUeberDenPoller() throws Exception {
        pollAntwortVorbereiten(1);
        fake.enqueueUpdate("message", nachricht(CHAT, "/quiz").get("message").toString());
        fake.enqueueUpdate("poll_answer", stimme("poll1", 7, "Anna", "[1]").get("poll_answer").toString());
        fake.enqueueUpdate("message", nachricht(CHAT, "/rangliste").get("message").toString());
        AtomicReference<Throwable> fehler = new AtomicReference<>();
        UpdatePoller poller = new UpdatePoller(new TelegramClient("123456:GEHEIM", fake.baseUrl()),
                List.of("message", "callback_query", "poll_answer"));
        Thread thread = new Thread(() -> {
            try {
                poller.laufen(bot.alsHandler());
            } catch (InterruptedException e) {
                // erwartetes Ende
            } catch (Throwable t) {
                fehler.set(t);
            }
        });
        thread.start();
        try {
            RecordedRequest liste = fake.awaitRequests("sendMessage", 1, Duration.ofSeconds(5)).get(0);
            assertTrue(liste.bodyContains("Anna: 1 von 1 richtig"));
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
        assertEquals(null, fehler.get());
        assertTrue(fake.requests("getUpdates").getLast()
                .bodyContains("\"allowed_updates\":[\"message\",\"callback_query\",\"poll_answer\"]"));
    }
}
