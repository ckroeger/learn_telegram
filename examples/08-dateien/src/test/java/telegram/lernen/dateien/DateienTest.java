package telegram.lernen.dateien;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.dateien.Dateien.DateiInfo;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class DateienTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path ordner;

    private FakeTelegramServer fake;
    private TelegramClient client;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("sendDocument", "{\"message_id\":5}");
        fake.onMethod("sendPhoto", "{\"message_id\":6}");
        client = new TelegramClient("123456:GEHEIM", fake.baseUrl());
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    // --- Empfangen

    @Test
    void infoLiestEinDokument() throws Exception {
        Optional<DateiInfo> info = Dateien.info(json("{\"document\":{\"file_id\":\"F1\",\"file_unique_id\":\"U1\","
                + "\"file_name\":\"bericht.pdf\",\"mime_type\":\"application/pdf\",\"file_size\":1234}}"));

        DateiInfo datei = info.orElseThrow();
        assertEquals("document", datei.typ());
        assertEquals("F1", datei.fileId());
        assertEquals("U1", datei.uniqueId());
        assertEquals("bericht.pdf", datei.name());
        assertEquals("application/pdf", datei.mimeType());
        assertEquals(1234, datei.groesse());
    }

    @Test
    void infoWaehltBeiFotosDieGroessteGroesseNachPixelnNichtNachReihenfolge() throws Exception {
        DateiInfo foto = Dateien.info(json("{\"photo\":["
                + "{\"file_id\":\"klein\",\"file_unique_id\":\"U1\",\"width\":90,\"height\":60,\"file_size\":1000},"
                + "{\"file_id\":\"gross\",\"file_unique_id\":\"U3\",\"width\":1280,\"height\":720,\"file_size\":85000},"
                + "{\"file_id\":\"mittel\",\"file_unique_id\":\"U2\",\"width\":320,\"height\":180,\"file_size\":9000}]}"))
                .orElseThrow();

        assertEquals("gross", foto.fileId());
        assertEquals(1280, foto.breite());
        assertEquals(720, foto.hoehe());
        assertEquals("photo", foto.typ());
        assertEquals(null, foto.name(), "Fotos haben keinen Dateinamen");
    }

    @Test
    void nachrichtenOhneDateiLiefernNichts() throws Exception {
        assertTrue(Dateien.info(json("{\"text\":\"Hallo\"}")).isEmpty());
        assertTrue(Dateien.info(json("{\"photo\":[]}")).isEmpty());
    }

    @Test
    void fehlendeDateigroesseWirdMinusEins() throws Exception {
        assertEquals(-1, Dateien.info(json("{\"document\":{\"file_id\":\"F\",\"file_unique_id\":\"U\"}}"))
                .orElseThrow().groesse());
    }

    // --- Namen

    @Test
    void sicherNameEntferntPfadeUndGefaehrlicheZeichen() {
        assertEquals("passwd", Dateien.sicherName("../../etc/passwd", "ersatz"));
        assertEquals("evil.exe", Dateien.sicherName("C:\\Users\\x\\evil.exe", "ersatz"));
        assertEquals("bashrc", Dateien.sicherName(".bashrc", "ersatz"), "keine versteckten Dateien");
        assertEquals("Bericht Q3 _final_.pdf", Dateien.sicherName("Bericht Q3 (final).pdf", "ersatz"));
        assertEquals("Übersicht-ä.pdf", Dateien.sicherName("Übersicht-ä.pdf", "ersatz"), "Umlaute bleiben");
        assertEquals("a_b_c.txt", Dateien.sicherName("a\nb\u0000c.txt", "ersatz"));
    }

    @Test
    void sicherNameFaelltAufDenErsatzZurueckWennNichtsUebrigBleibt() {
        for (String name : new String[] {null, "", "..", "...", "   ", "/", "a/..", "../.."}) {
            assertEquals("ersatz", Dateien.sicherName(name, "ersatz"), String.valueOf(name));
        }
    }

    @Test
    void sicherNameKuerztLangeNamenAmAnfangUndBehaeltDieEndung() {
        String name = Dateien.sicherName("x".repeat(500) + ".pdf", "ersatz");

        assertEquals(100, name.length());
        assertTrue(name.endsWith(".pdf"));
    }

    // --- Herunterladen

    private void getFileBereit(String fileId, String dateiPfad, byte[] inhalt) {
        fake.onMethod("getFile", "{\"file_id\":\"" + fileId + "\",\"file_unique_id\":\"U\",\"file_size\":"
                + inhalt.length + ",\"file_path\":\"" + dateiPfad + "\"}");
        fake.dateiBereitstellen(dateiPfad, inhalt);
    }

    @Test
    void herunterladenSpeichertUnterEindeutigemSicherenNamen() throws Exception {
        byte[] inhalt = {1, 2, 3, (byte) 0xFF};
        getFileBereit("F1", "documents/file_1.bin", inhalt);
        DateiInfo datei = new DateiInfo("document", "F1", "UNIQ", "daten.bin", "application/octet-stream", 4, 0, 0);

        Path gespeichert = Dateien.herunterladen(client, datei, ordner.resolve("empfangen"));

        assertEquals("UNIQ_daten.bin", gespeichert.getFileName().toString());
        assertTrue(java.util.Arrays.equals(inhalt, Files.readAllBytes(gespeichert)));
        assertTrue(fake.requests("getFile").get(0).bodyContains("\"file_id\":\"F1\""));
    }

    @Test
    void eingeschmuggelterPfadImDateinamenLandetTrotzdemImZielordner() throws Exception {
        getFileBereit("F1", "documents/file_1.bin", new byte[] {7});
        DateiInfo datei = new DateiInfo("document", "F1", "UNIQ", "../../../tmp/boese.sh", "text/x-sh", 1, 0, 0);
        Path ziel = ordner.resolve("empfangen");

        Path gespeichert = Dateien.herunterladen(client, datei, ziel);

        assertEquals(ziel.toAbsolutePath().normalize(), gespeichert.toAbsolutePath().getParent());
        assertEquals("UNIQ_boese.sh", gespeichert.getFileName().toString());
        assertFalse(Files.exists(ordner.resolve("boese.sh")));
    }

    @Test
    void zuGrosseDateienWerdenGarNichtErstAngefragt() throws Exception {
        DateiInfo gross = new DateiInfo("document", "F1", "U", "film.mp4", "video/mp4", Dateien.MAX_DOWNLOAD + 1, 0, 0);

        assertThrows(IOException.class, () -> Dateien.herunterladen(client, gross, ordner));

        assertEquals(0, fake.requests("getFile").size());
    }

    @Test
    void genauAmLimitWirdHeruntergeladen() throws Exception {
        getFileBereit("F1", "documents/file_1.bin", new byte[] {1});
        DateiInfo grenze = new DateiInfo("document", "F1", "U", "a.bin", null, Dateien.MAX_DOWNLOAD, 0, 0);

        Dateien.herunterladen(client, grenze, ordner);

        assertEquals(1, fake.requests("getFile").size());
    }

    // --- Senden

    @Test
    void dokumentHochladenSendetDieDateiAlsMultipart() throws Exception {
        Path datei = Files.writeString(ordner.resolve("notiz.txt"), "Inhalt der Notiz");

        Dateien.dokumentHochladen(client, 42, datei, "Meine Notiz");

        RecordedRequest aufruf = fake.requests("sendDocument").get(0);
        assertTrue(aufruf.bodyContains("name=\"chat_id\""));
        assertTrue(aufruf.bodyContains("Meine Notiz"));
        assertTrue(aufruf.bodyContains("filename=\"notiz.txt\""));
        assertTrue(aufruf.bodyContains("Inhalt der Notiz"));
    }

    @Test
    void berichtHochladenErzeugtDieDateiImSpeicher() throws Exception {
        Dateien.berichtHochladen(client, 42, "bericht.txt", "Zeile 1\nZeile 2 mit Ü\n");

        RecordedRequest aufruf = fake.requests("sendDocument").get(0);
        assertTrue(aufruf.bodyContains("filename=\"bericht.txt\""));
        assertTrue(aufruf.bodyContains("Content-Type: text/plain; charset=utf-8"));
        assertTrue(aufruf.bodyContains("Zeile 2 mit Ü"));
    }

    @Test
    void erneutSendenUndFotoPerUrlGehenAlsJsonOhneUpload() throws Exception {
        Dateien.dokumentErneutSenden(client, 42, "FILE_ID_1", "nochmal");
        Dateien.fotoPerUrlSenden(client, 42, "https://beispiel.example/foto.jpg", "per URL");

        JsonNode dokument = json(fake.requests("sendDocument").get(0).body());
        assertEquals("FILE_ID_1", dokument.get("document").asText());
        assertEquals("nochmal", dokument.get("caption").asText());
        JsonNode foto = json(fake.requests("sendPhoto").get(0).body());
        assertEquals("https://beispiel.example/foto.jpg", foto.get("photo").asText());
    }
}
