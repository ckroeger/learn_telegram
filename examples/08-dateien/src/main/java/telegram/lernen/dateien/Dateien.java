package telegram.lernen.dateien;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import telegram.lernen.client.Dateianhang;
import telegram.lernen.client.TelegramClient;

/** Dateien senden, empfangen und speichern. */
public final class Dateien {

    /** So große Dateien darf ein Bot laut Doku herunterladen (getFile). */
    public static final long MAX_DOWNLOAD = 20L * 1024 * 1024;

    private Dateien() {
    }

    /** Was Telegram über eine empfangene Datei mitteilt. {@code name} fehlt bei Fotos. */
    public record DateiInfo(String typ, String fileId, String uniqueId, String name, String mimeType, long groesse,
            int breite, int hoehe) {
    }

    // #region senden
    /** Lädt eine Datei von der Festplatte hoch. Dokumente dürfen bis 50 MB groß sein. */
    public static JsonNode dokumentHochladen(TelegramClient client, long chatId, Path datei, String beschriftung)
            throws IOException, InterruptedException {
        return client.hochladen("sendDocument",
                TelegramClient.parameter().put("chat_id", chatId).put("caption", beschriftung),
                Dateianhang.von("document", datei));
    }

    /** Erzeugt eine Datei im Speicher und lädt sie hoch, ganz ohne Festplatte. */
    public static JsonNode berichtHochladen(TelegramClient client, long chatId, String dateiname, String text)
            throws IOException, InterruptedException {
        return client.hochladen("sendDocument", TelegramClient.parameter().put("chat_id", chatId),
                Dateianhang.von("document", dateiname, "text/plain; charset=utf-8", text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Sendet eine Datei, die schon bei Telegram liegt: nur die file_id, kein Upload, keine Größengrenze. */
    public static JsonNode dokumentErneutSenden(TelegramClient client, long chatId, String fileId, String beschriftung)
            throws IOException, InterruptedException {
        return client.aufrufen("sendDocument", TelegramClient.parameter()
                .put("chat_id", chatId).put("document", fileId).put("caption", beschriftung));
    }

    /** Telegram lädt das Foto selbst von der Adresse: bis 5 MB, und die Adresse muss öffentlich sein. */
    public static JsonNode fotoPerUrlSenden(TelegramClient client, long chatId, String url, String beschriftung)
            throws IOException, InterruptedException {
        return client.aufrufen("sendPhoto", TelegramClient.parameter()
                .put("chat_id", chatId).put("photo", url).put("caption", beschriftung));
    }
    // #endregion senden

    // #region empfangen
    /** Liest ein Dokument oder Foto aus einer Nachricht. Bei einem Foto zählt die größte der gelieferten Größen. */
    public static Optional<DateiInfo> info(JsonNode nachricht) {
        JsonNode dokument = nachricht.path("document");
        if (dokument.isObject()) {
            return Optional.of(new DateiInfo("document", dokument.path("file_id").asText(),
                    dokument.path("file_unique_id").asText(), dokument.path("file_name").asText(null),
                    dokument.path("mime_type").asText(null), dokument.path("file_size").asLong(-1), 0, 0));
        }
        JsonNode foto = groessteFoto(nachricht.path("photo"));
        if (foto != null) {
            return Optional.of(new DateiInfo("photo", foto.path("file_id").asText(),
                    foto.path("file_unique_id").asText(), null, "image/jpeg", foto.path("file_size").asLong(-1),
                    foto.path("width").asInt(), foto.path("height").asInt()));
        }
        return Optional.empty();
    }

    /** Ein Foto kommt als Liste von {@code PhotoSize} in mehreren Auflösungen. Die größte hat die meisten Pixel. */
    static JsonNode groessteFoto(JsonNode groessen) {
        JsonNode groesste = null;
        for (JsonNode groesse : groessen) {
            long pixel = groesse.path("width").asLong() * groesse.path("height").asLong();
            if (groesste == null || pixel > groesste.path("width").asLong() * groesste.path("height").asLong()) {
                groesste = groesse;
            }
        }
        return groesste;
    }
    // #endregion empfangen

    // #region herunterladen
    /**
     * Lädt eine Datei in den Ordner. Der Dateiname kommt vom Absender und ist daher nicht vertrauenswürdig:
     * {@link #sicherName} macht daraus einen harmlosen Namen, und die {@code file_unique_id} verhindert,
     * dass zwei gleich benannte Dateien sich überschreiben.
     *
     * @return der Pfad der gespeicherten Datei
     */
    public static Path herunterladen(TelegramClient client, DateiInfo datei, Path ordner)
            throws IOException, InterruptedException {
        if (datei.groesse() > MAX_DOWNLOAD) {
            throw new IOException("Datei ist größer als das Limit von 20 MB");
        }
        JsonNode dateiInfo = client.aufrufen("getFile", TelegramClient.parameter().put("file_id", datei.fileId()));
        Files.createDirectories(ordner);
        String name = datei.uniqueId() + "_" + sicherName(datei.name(), datei.typ());
        Path ziel = ordner.resolve(name).normalize();
        if (!ziel.startsWith(ordner.normalize())) {
            throw new IOException("Zielpfad liegt außerhalb des Ordners");
        }
        client.herunterladen(dateiInfo.path("file_path").asText(), ziel);
        return ziel;
    }

    /** Macht einen vom Absender gelieferten Namen zu einem harmlosen: ohne Pfad, ohne Sonderzeichen. */
    static String sicherName(String name, String ersatz) {
        String nurName = name == null ? "" : name.replace('\\', '/');
        nurName = nurName.substring(nurName.lastIndexOf('/') + 1);
        nurName = nurName.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        nurName = nurName.replaceAll("^[.\\s]+", "");
        if (nurName.length() > 100) {
            nurName = nurName.substring(nurName.length() - 100);
        }
        return nurName.isBlank() ? ersatz : nurName;
    }
    // #endregion herunterladen
}
