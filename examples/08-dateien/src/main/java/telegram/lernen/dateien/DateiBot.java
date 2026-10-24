package telegram.lernen.dateien;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import telegram.lernen.client.Befehl;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.TelegramException;
import telegram.lernen.client.UpdatePoller;
import telegram.lernen.dateien.Dateien.DateiInfo;

/**
 * Datei-Bot: speichert Dokumente und Fotos, die man ihm schickt, und sendet sie mit {@code file_id} zurück.
 * {@code /datei} lädt eine im Speicher erzeugte Textdatei hoch.
 */
public class DateiBot {

    private final TelegramClient client;
    private final String eigenerName;
    private final Path ordner;

    DateiBot(TelegramClient client, String eigenerName, Path ordner) {
        this.client = client;
        this.eigenerName = eigenerName;
        this.ordner = ordner;
    }

    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        Path ordner = Path.of(System.getenv().getOrDefault("DATEI_ORDNER", "empfangen"));
        DateiBot bot = new DateiBot(client, eigenerName, ordner);
        System.out.println("@" + eigenerName + " läuft, gespeichert wird in " + ordner.toAbsolutePath()
                + ". Strg+C beendet.");
        new UpdatePoller(client, List.of("message")).laufen(update -> {
            try {
                bot.behandle(update);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void behandle(JsonNode update) throws IOException, InterruptedException {
        JsonNode nachricht = update.path("message");
        long chatId = nachricht.path("chat").path("id").asLong();
        Optional<DateiInfo> datei = Dateien.info(nachricht);
        if (datei.isPresent()) {
            dateiVerarbeiten(chatId, datei.get());
            return;
        }
        Optional<Befehl> befehl = Befehl.aus(nachricht, eigenerName);
        if (befehl.isPresent() && befehl.get().name().equals("datei")) {
            Dateien.berichtHochladen(client, chatId, "gruss.txt", "Hallo aus Java!\nDiese Datei wurde im Speicher erzeugt.\n");
        } else if (befehl.isPresent() || !Befehl.beginntMitBefehl(nachricht)) {
            // Text und eigene, unbekannte Befehle bekommen den Hinweis. Befehle an andere Bots bleiben unbeantwortet.
            senden(chatId, "Schicke mir ein Dokument oder ein Foto. /datei sendet dir eine Beispieldatei.");
        }
    }

    private void dateiVerarbeiten(long chatId, DateiInfo datei) throws IOException, InterruptedException {
        String beschreibung = datei.typ().equals("photo")
                ? "Foto " + datei.breite() + "x" + datei.hoehe()
                : "Dokument " + datei.name() + " (" + datei.mimeType() + ")";
        String ergebnis;
        if (datei.groesse() > Dateien.MAX_DOWNLOAD) {
            ergebnis = "Zu groß zum Herunterladen (Limit 20 MB), ich schicke sie nur zurück.";
        } else {
            try {
                ergebnis = "Gespeichert als " + Dateien.herunterladen(client, datei, ordner).getFileName();
            } catch (IOException | TelegramException e) {
                // Speichern darf scheitern (Netz, Telegram lehnt die Datei ab), das Zurücksenden klappt trotzdem.
                ergebnis = "Speichern fehlgeschlagen (" + e.getClass().getSimpleName() + "), ich schicke sie nur zurück.";
            }
        }
        senden(chatId, beschreibung + ", " + datei.groesse() + " Bytes. " + ergebnis);
        // Erneut senden geht mit der file_id, ohne Upload. Der Typ bleibt gleich: ein Foto bleibt ein Foto.
        String methode = datei.typ().equals("photo") ? "sendPhoto" : "sendDocument";
        String feld = datei.typ().equals("photo") ? "photo" : "document";
        client.aufrufen(methode, TelegramClient.parameter()
                .put("chat_id", chatId).put(feld, datei.fileId()).put("caption", "Das hast du geschickt"));
    }

    private void senden(long chatId, String text) throws IOException, InterruptedException {
        client.aufrufen("sendMessage", TelegramClient.parameter().put("chat_id", chatId).put("text", text));
    }
}
