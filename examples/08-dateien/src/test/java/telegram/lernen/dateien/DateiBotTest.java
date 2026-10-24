package telegram.lernen.dateien;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.testsupport.FakeTelegramServer;

class DateiBotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CHAT = 42;

    @TempDir
    Path ordner;

    private FakeTelegramServer fake;
    private DateiBot bot;
    private Path ablage;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("sendDocument", "{\"message_id\":5}");
        fake.onMethod("sendPhoto", "{\"message_id\":6}");
        ablage = ordner.resolve("empfangen");
        bot = new DateiBot(new TelegramClient("123456:GEHEIM", fake.baseUrl()), "fake_bot", ablage);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode nachricht(String felder) throws Exception {
        return JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":9,\"chat\":{\"id\":" + CHAT
                + ",\"type\":\"private\"},\"date\":1," + felder + "}}");
    }

    private static JsonNode befehl(String text) throws Exception {
        return nachricht("\"text\":\"" + text + "\",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":"
                + text.length() + "}]");
    }

    private void getFileBereit(String dateiPfad, byte[] inhalt) {
        fake.onMethod("getFile", "{\"file_id\":\"x\",\"file_unique_id\":\"U\",\"file_size\":" + inhalt.length
                + ",\"file_path\":\"" + dateiPfad + "\"}");
        fake.dateiBereitstellen(dateiPfad, inhalt);
    }

    private JsonNode letzte(String methode) throws Exception {
        return JSON.readTree(fake.requests(methode).getLast().body());
    }

    private List<String> gespeicherteDateien() throws Exception {
        if (!Files.exists(ablage)) {
            return List.of();
        }
        try (Stream<Path> dateien = Files.list(ablage)) {
            return dateien.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void einDokumentWirdGespeichertBeschriebenUndPerFileIdZurueckgeschickt() throws Exception {
        getFileBereit("documents/file_1.txt", "Hallo Datei".getBytes());

        bot.behandle(nachricht("\"document\":{\"file_id\":\"DOC1\",\"file_unique_id\":\"UNI1\","
                + "\"file_name\":\"notiz.txt\",\"mime_type\":\"text/plain\",\"file_size\":11}"));

        assertEquals(List.of("UNI1_notiz.txt"), gespeicherteDateien());
        assertEquals("Hallo Datei", Files.readString(ablage.resolve("UNI1_notiz.txt")));
        String text = letzte("sendMessage").get("text").asText();
        assertTrue(text.contains("Dokument notiz.txt (text/plain)"), text);
        assertTrue(text.contains("Gespeichert als UNI1_notiz.txt"), text);
        JsonNode zurueck = letzte("sendDocument");
        assertEquals("DOC1", zurueck.get("document").asText());
        assertEquals(CHAT, zurueck.get("chat_id").asLong());
    }

    @Test
    void einFotoWirdInDerGroesstenGroesseGespeichertUndAlsFotoZurueckgeschickt() throws Exception {
        getFileBereit("photos/file_2.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});

        bot.behandle(nachricht("\"photo\":["
                + "{\"file_id\":\"P_KLEIN\",\"file_unique_id\":\"UK\",\"width\":90,\"height\":60,\"file_size\":900},"
                + "{\"file_id\":\"P_GROSS\",\"file_unique_id\":\"UG\",\"width\":1280,\"height\":720,\"file_size\":4}]"));

        assertEquals(List.of("UG_photo"), gespeicherteDateien());
        assertTrue(letzte("sendMessage").get("text").asText().contains("Foto 1280x720"));
        assertEquals("P_GROSS", letzte("sendPhoto").get("photo").asText());
        assertEquals(0, fake.requests("sendDocument").size(), "ein Foto bleibt ein Foto");
    }

    @Test
    void zuGrosseDateienWerdenNichtHeruntergeladenAberZurueckgeschickt() throws Exception {
        bot.behandle(nachricht("\"document\":{\"file_id\":\"BIG\",\"file_unique_id\":\"UB\",\"file_name\":\"film.mp4\","
                + "\"mime_type\":\"video/mp4\",\"file_size\":" + (Dateien.MAX_DOWNLOAD + 1) + "}"));

        assertEquals(0, fake.requests("getFile").size());
        assertEquals(List.of(), gespeicherteDateien());
        assertTrue(letzte("sendMessage").get("text").asText().contains("Zu groß"));
        assertEquals("BIG", letzte("sendDocument").get("document").asText());
    }

    @Test
    void scheitertDasSpeichernWirdTrotzdemZurueckgeschickt() throws Exception {
        fake.onMethod("getFile", "{\"file_id\":\"x\",\"file_unique_id\":\"U\",\"file_path\":\"documents/fehlt.bin\"}");
        // Die Datei wird nicht bereitgestellt: der Download liefert 404.

        bot.behandle(nachricht("\"document\":{\"file_id\":\"D\",\"file_unique_id\":\"U\",\"file_name\":\"a.bin\","
                + "\"mime_type\":\"application/octet-stream\",\"file_size\":3}"));

        assertTrue(letzte("sendMessage").get("text").asText().contains("Speichern fehlgeschlagen (IOException)"));
        assertEquals("D", letzte("sendDocument").get("document").asText());
        assertFalse(Files.exists(ablage.resolve("U_a.bin")), "keine halbe Datei zurücklassen");
    }

    @Test
    void einBoeserDateinameBleibtImOrdner() throws Exception {
        getFileBereit("documents/file_3.sh", new byte[] {1});

        bot.behandle(nachricht("\"document\":{\"file_id\":\"D\",\"file_unique_id\":\"UX\","
                + "\"file_name\":\"../../../.ssh/authorized_keys\",\"mime_type\":\"text/plain\",\"file_size\":1}"));

        assertEquals(List.of("UX_authorized_keys"), gespeicherteDateien());
        assertFalse(Files.exists(ordner.resolve(".ssh")));
    }

    @Test
    void dateiBefehlLaedtEineImSpeicherErzeugteDateiHoch() throws Exception {
        bot.behandle(befehl("/datei"));

        var aufruf = fake.requests("sendDocument").get(0);
        assertTrue(aufruf.bodyContains("filename=\"gruss.txt\""));
        assertTrue(aufruf.bodyContains("Hallo aus Java!"));
        assertEquals(0, fake.requests("sendMessage").size());
    }

    @Test
    void textUndUnbekannteBefehleBekommenDenHinweisBefehleAnAndereBotsNicht() throws Exception {
        bot.behandle(nachricht("\"text\":\"Hallo\""));
        assertTrue(letzte("sendMessage").get("text").asText().startsWith("Schicke mir ein Dokument"));

        bot.behandle(befehl("/gibtsnicht"));
        assertEquals(2, fake.requests("sendMessage").size());

        bot.behandle(befehl("/datei@anderer_bot"));
        assertEquals(2, fake.requests("sendMessage").size());
        assertEquals(0, fake.requests("sendDocument").size());
    }
}
