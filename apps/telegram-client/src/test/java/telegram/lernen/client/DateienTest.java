package telegram.lernen.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.lernen.testsupport.FakeTelegramServer;
import telegram.lernen.testsupport.RecordedRequest;

class DateienTest {

    private static final String TOKEN = "123456:GEHEIM";

    @TempDir
    Path ordner;

    private FakeTelegramServer fake;
    private TelegramClient client;

    @BeforeEach
    void start() throws Exception {
        fake = FakeTelegramServer.start();
        fake.onMethod("sendDocument", "{\"message_id\":5}");
        client = new TelegramClient(TOKEN, fake.baseUrl());
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    /** Die Teile einer Multipart-Anfrage, aufgetrennt an der Grenze. Latin-1, damit Bytes 1:1 erhalten bleiben. */
    private static List<String> teile(byte[] koerper) {
        String text = new String(koerper, StandardCharsets.ISO_8859_1);
        String grenze = text.substring(2, text.indexOf("\r\n")); // erste Zeile: --grenze
        assertTrue(text.endsWith("--" + grenze + "--\r\n"), "Abschlusszeile fehlt");
        String ohneEnde = text.substring(0, text.length() - ("--" + grenze + "--\r\n").length());
        return Arrays.stream(ohneEnde.split("--" + java.util.regex.Pattern.quote(grenze) + "\r\n"))
                .filter(t -> !t.isEmpty()).toList();
    }

    private static String kopf(String teil) {
        return teil.substring(0, teil.indexOf("\r\n\r\n"));
    }

    private static String inhalt(String teil) {
        String rest = teil.substring(teil.indexOf("\r\n\r\n") + 4);
        return rest.substring(0, rest.length() - 2); // das Zeilenende nach dem Inhalt gehört nicht dazu
    }

    @Test
    void ladeEineBinaereDateiUnveraendertHochUndSendeAlleParameterAlsFelder() throws Exception {
        byte[] binaer = new byte[300];
        for (int i = 0; i < binaer.length; i++) {
            binaer[i] = (byte) i; // enthält 0x00, 0x0D, 0x0A, 0xFF und auch die Folge "--"
        }
        Path datei = Files.write(ordner.resolve("daten.bin"), binaer);

        JsonNode ergebnis = client.hochladen("sendDocument", TelegramClient.parameter()
                        .put("chat_id", 42).put("caption", "Mit Umlaut ä").put("disable_notification", true),
                Dateianhang.von("document", datei));

        assertEquals(5, ergebnis.get("message_id").asLong());
        RecordedRequest aufruf = fake.requests("sendDocument").get(0);
        List<String> teile = teile(aufruf.bodyBytes());
        assertEquals(4, teile.size(), "drei Felder und eine Datei");
        assertTrue(kopf(teile.get(0)).contains("name=\"chat_id\""));
        assertEquals("42", inhalt(teile.get(0)));
        assertEquals("true", inhalt(teile.get(2)));
        String dateiTeil = teile.get(3);
        assertTrue(kopf(dateiTeil).contains("name=\"document\"; filename=\"daten.bin\""));
        assertArrayEquals(binaer, inhalt(dateiTeil).getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(TOKEN, aufruf.token());
    }

    @Test
    void textfelderMitUmlautenKommenAlsUtf8An() throws Exception {
        client.hochladen("sendDocument", TelegramClient.parameter().put("chat_id", 1).put("caption", "Grüße ä ö ü €"),
                Dateianhang.von("document", "a.txt", "text/plain", "x".getBytes()));

        List<String> teile = teile(fake.requests("sendDocument").get(0).bodyBytes());
        String caption = new String(inhalt(teile.get(1)).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertEquals("Grüße ä ö ü €", caption);
    }

    @Test
    void objekteWieReplyMarkupGehenAlsJsonText() throws Exception {
        var tasten = Tastaturen.inline(List.of(List.of(Tastaturen.Taste.daten("Ja", "ja"))));
        var parameter = TelegramClient.parameter().put("chat_id", 1);
        parameter.set("reply_markup", tasten);

        client.hochladen("sendDocument", parameter, Dateianhang.von("document", "a.txt", "text/plain", new byte[0]));

        List<String> teile = teile(fake.requests("sendDocument").get(0).bodyBytes());
        assertEquals("{\"inline_keyboard\":[[{\"text\":\"Ja\",\"callback_data\":\"ja\"}]]}", inhalt(teile.get(1)));
    }

    @Test
    void dateienAusBytesMitEigenemNamenUndTyp() throws Exception {
        client.hochladen("sendDocument", TelegramClient.parameter().put("chat_id", 1),
                Dateianhang.von("document", "bericht.csv", "text/csv", "a;b\n1;2\n".getBytes()));

        String dateiTeil = teile(fake.requests("sendDocument").get(0).bodyBytes()).get(1);
        assertTrue(kopf(dateiTeil).contains("filename=\"bericht.csv\""));
        assertTrue(kopf(dateiTeil).contains("Content-Type: text/csv"));
        assertEquals("a;b\n1;2\n", inhalt(dateiTeil));
    }

    @Test
    void gefaehrlicheDateinamenKoennenDenAufbauDerAnfrageNichtVeraendern() throws Exception {
        String boese = "a\"b\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n999";

        client.hochladen("sendDocument", TelegramClient.parameter().put("chat_id", 42),
                Dateianhang.von("document", boese, "text/plain", "x".getBytes()));

        List<String> teile = teile(fake.requests("sendDocument").get(0).bodyBytes());
        assertEquals(2, teile.size(), "nur das echte Feld und die Datei, kein eingeschmuggeltes drittes");
        assertEquals("42", inhalt(teile.get(0)));
        assertTrue(kopf(teile.get(1)).contains("filename=\"a%22b%0D%0AContent-Disposition: form-data; name=%22chat_id%22"));
    }

    @Test
    void maskierenErsetztNurDieDreiGefaehrlichenZeichen() {
        assertEquals("a%22b%0D%0Ac", Multipart.maskieren("a\"b\r\nc"));
        assertEquals("Bericht ä-1.pdf", Multipart.maskieren("Bericht ä-1.pdf"));
    }

    @Test
    void grosseDateienWerdenOhneVerlustUebertragen() throws Exception {
        byte[] gross = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(gross);
        Path datei = Files.write(ordner.resolve("gross.bin"), gross);

        client.hochladen("sendDocument", TelegramClient.parameter().put("chat_id", 1), Dateianhang.von("document", datei));

        String dateiTeil = teile(fake.requests("sendDocument").get(0).bodyBytes()).get(1);
        assertArrayEquals(gross, inhalt(dateiTeil).getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void telegramFehlerBeimUploadWerdenAlsExceptionWeitergegeben() throws Exception {
        fake.failNext("sendDocument", 413,
                "{\"ok\":false,\"error_code\":413,\"description\":\"Request Entity Too Large\"}");

        TelegramException fehler = assertThrows(TelegramException.class, () -> client.hochladen("sendDocument",
                TelegramClient.parameter().put("chat_id", 1), Dateianhang.von("document", "a", "text/plain", new byte[1])));

        assertEquals(413, fehler.errorCode());
        assertFalse(fehler.getMessage().contains("GEHEIM"));
    }

    @Test
    void eineFehlendeDateiIstEinIoFehlerUndSendetNichts() throws Exception {
        Path fehlt = ordner.resolve("gibt-es-nicht.pdf");

        assertThrows(IOException.class, () -> client.hochladen("sendDocument",
                TelegramClient.parameter().put("chat_id", 1), Dateianhang.von("document", fehlt)));

        assertEquals(0, fake.requests("sendDocument").size());
    }

    @Test
    void dateianhangPruftSeineEingaben() {
        assertThrows(IllegalArgumentException.class, () -> new Dateianhang("", "a", "x/y", null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Dateianhang("f", "", "x/y", null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Dateianhang("f", "a", "x/y", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Dateianhang("f", "a", "x/y", Path.of("x"), new byte[0]));
    }

    // --- Download

    @Test
    void herunterladenSchreibtDieBytesUnveraendert() throws Exception {
        byte[] inhalt = {0, 1, (byte) 0xFF, 10, 13, 42};
        fake.dateiBereitstellen("documents/file_7.bin", inhalt);
        Path ziel = ordner.resolve("heruntergeladen.bin");

        client.herunterladen("documents/file_7.bin", ziel);

        assertArrayEquals(inhalt, Files.readAllBytes(ziel));
        RecordedRequest aufruf = fake.requests("file").get(0);
        assertEquals(TOKEN, aufruf.token());
        assertEquals("documents/file_7.bin", aufruf.body());
    }

    @Test
    void herunterladenErsetztEineVorhandeneDatei() throws Exception {
        fake.dateiBereitstellen("a.txt", "neu".getBytes());
        Path ziel = Files.writeString(ordner.resolve("ziel.txt"), "alt");

        client.herunterladen("a.txt", ziel);

        assertEquals("neu", Files.readString(ziel));
    }

    @Test
    void einFehlerBeimDownloadHinterlaesstKeineDateiUndNenntDenTokenNicht() throws Exception {
        Path ziel = ordner.resolve("soll-nicht-entstehen.bin");

        IOException fehler = assertThrows(IOException.class, () -> client.herunterladen("nicht/da.bin", ziel));

        assertTrue(fehler.getMessage().contains("404"));
        assertFalse(fehler.getMessage().contains("GEHEIM"));
        assertFalse(Files.exists(ziel));
    }

    @Test
    void ungueltigeDateipfadeWerdenAbgelehntBevorEineAnfrageEntsteht() {
        for (String pfad : new String[] {"../geheim", "a/../b", "/etc/passwd", "a b", "http://x.example/y", "", "a?x=1",
                "a#b", "ä.txt"}) {
            assertThrows(IllegalArgumentException.class, () -> client.herunterladen(pfad, ordner.resolve("x")), pfad);
        }
        assertEquals(0, fake.requests().size());
    }
}
