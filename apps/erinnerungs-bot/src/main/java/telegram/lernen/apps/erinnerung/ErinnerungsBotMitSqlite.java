package telegram.lernen.apps.erinnerung;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/**
 * Der Erinnerungs-Bot mit SQLite: Erinnerungen überleben einen Neustart.
 * Die Datei steht in {@code ERINNERUNGEN_DB} (Standard: {@code erinnerungen.db} im aktuellen Ordner).
 */
public class ErinnerungsBotMitSqlite {

    public static void main(String[] args) throws Exception {
        String pfad = System.getenv().getOrDefault("ERINNERUNGEN_DB", "erinnerungen.db");
        TelegramClient client = TelegramClient.ausUmgebung();
        String eigenerName = client.aufrufen("getMe").get("username").asText();
        Clock uhr = Clock.systemUTC();

        try (SqliteSpeicher speicher = SqliteSpeicher.oeffnen(pfad)) {
            // Ausgelöste, nie erledigte Erinnerungen nach 30 Tagen entfernen, sonst wächst die Datei ewig.
            speicher.aufraeumen(uhr.instant().minus(Duration.ofDays(30)));

            ErinnerungsBot bot = new ErinnerungsBot(client, eigenerName, speicher, Planer.echt(), uhr);
            bot.befehleRegistrieren();
            int wieder = bot.wiederaufnehmen();
            System.out.println("@" + eigenerName + " läuft mit " + pfad + ", " + wieder
                    + " Erinnerung(en) wieder aufgenommen. Strg+C beendet.");
            new UpdatePoller(client, List.of("message", "callback_query")).laufen(bot.alsHandler());
        }
    }
}
