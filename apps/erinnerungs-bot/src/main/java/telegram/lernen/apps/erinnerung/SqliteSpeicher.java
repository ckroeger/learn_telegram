package telegram.lernen.apps.erinnerung;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Erinnerungen in einer SQLite-Datei: Sie bleiben beim Neustart erhalten.
 *
 * <p>Eine Verbindung für das ganze Programm, alle Methoden sind {@code synchronized}: Die Update-Schleife und der
 * Timer-Thread greifen gleichzeitig zu, SQLite erlaubt aber nur einen Schreiber. Jede Abfrage ist ein
 * {@link PreparedStatement}, denn der Text der Erinnerung kommt vom Nutzer.
 */
final class SqliteSpeicher implements Erinnerungsspeicher, AutoCloseable {

    /** Ein Fehler der Datenbank. Der Bot kann ihn nicht beheben, deshalb ungeprüft. */
    static final class SpeicherException extends RuntimeException {
        SpeicherException(String meldung, SQLException ursache) {
            super(meldung, ursache);
        }
    }

    private static final String SPALTEN = "id, chat_id, text, status_nachricht_id, faellig_um";

    private final Connection verbindung;

    private SqliteSpeicher(Connection verbindung) {
        this.verbindung = verbindung;
    }

    // #region oeffnen
    /** Öffnet die Datei (oder legt sie an) und stellt das Schema sicher. {@code ":memory:"} ist nur für Tests. */
    static SqliteSpeicher oeffnen(String pfad) {
        try {
            Connection verbindung = DriverManager.getConnection("jdbc:sqlite:" + pfad);
            try (Statement anweisung = verbindung.createStatement()) {
                // WAL: Leser (z. B. die sqlite3-Konsole) stören den Bot nicht. busy_timeout: bei einer Sperre
                // bis zu 5 Sekunden warten, statt sofort mit "database is locked" zu scheitern.
                anweisung.execute("PRAGMA journal_mode = WAL");
                anweisung.execute("PRAGMA busy_timeout = 5000");
                anweisung.execute("""
                        CREATE TABLE IF NOT EXISTS erinnerung (
                            id                INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id           INTEGER NOT NULL,
                            text              TEXT    NOT NULL,
                            status_nachricht_id INTEGER NOT NULL,
                            faellig_um        INTEGER NOT NULL,
                            zustand           TEXT    NOT NULL CHECK (zustand IN ('OFFEN', 'AUSGELOEST'))
                        )""");
                anweisung.execute("CREATE INDEX IF NOT EXISTS erinnerung_zustand ON erinnerung (zustand, faellig_um)");
            }
            return new SqliteSpeicher(verbindung);
        } catch (SQLException e) {
            throw new SpeicherException("Datenbank konnte nicht geöffnet werden", e);
        }
    }
    // #endregion oeffnen

    @Override
    public synchronized Erinnerung anlegen(long chatId, String text, long statusNachrichtId, Instant faelligUm) {
        String sql = "INSERT INTO erinnerung (chat_id, text, status_nachricht_id, faellig_um, zustand) "
                + "VALUES (?, ?, ?, ?, 'OFFEN')";
        try (PreparedStatement anweisung = verbindung.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            anweisung.setLong(1, chatId);
            anweisung.setString(2, text);
            anweisung.setLong(3, statusNachrichtId);
            anweisung.setLong(4, faelligUm.toEpochMilli());
            anweisung.executeUpdate();
            try (ResultSet schluessel = anweisung.getGeneratedKeys()) {
                schluessel.next();
                return new Erinnerung(schluessel.getLong(1), chatId, text, statusNachrichtId, faelligUm);
            }
        } catch (SQLException e) {
            throw new SpeicherException("Erinnerung konnte nicht gespeichert werden", e);
        }
    }

    @Override
    public synchronized Optional<Erinnerung> finden(long id, Zustand zustand) {
        List<Erinnerung> treffer = abfragen("SELECT " + SPALTEN + " FROM erinnerung WHERE id = ? AND zustand = ?",
                id, zustand.name());
        return treffer.stream().findFirst();
    }

    // #region wechseln
    /**
     * Der Zustandswechsel ist ein einziges UPDATE mit der Bedingung {@code zustand = von}. Die Datenbank führt es
     * unteilbar aus: Genau ein Aufruf ändert eine Zeile ({@code true}), alle anderen ändern keine ({@code false}).
     */
    @Override
    public synchronized boolean wechseln(long id, Zustand von, Zustand nach) {
        return aendern("UPDATE erinnerung SET zustand = ? WHERE id = ? AND zustand = ?",
                nach.name(), id, von.name()) == 1;
    }

    @Override
    public synchronized boolean entfernen(long id, Zustand zustand) {
        return aendern("DELETE FROM erinnerung WHERE id = ? AND zustand = ?", id, zustand.name()) == 1;
    }
    // #endregion wechseln

    @Override
    public synchronized void aktualisieren(long id, long statusNachrichtId, Instant faelligUm) {
        aendern("UPDATE erinnerung SET status_nachricht_id = ?, faellig_um = ? WHERE id = ?",
                statusNachrichtId, faelligUm.toEpochMilli(), id);
    }

    @Override
    public synchronized long anzahlOffen(long chatId) {
        try (PreparedStatement anweisung = verbindung.prepareStatement(
                "SELECT COUNT(*) FROM erinnerung WHERE chat_id = ? AND zustand = 'OFFEN'")) {
            anweisung.setLong(1, chatId);
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                ergebnis.next();
                return ergebnis.getLong(1);
            }
        } catch (SQLException e) {
            throw new SpeicherException("Abfrage fehlgeschlagen", e);
        }
    }

    @Override
    public synchronized List<Erinnerung> offene(long chatId) {
        return abfragen("SELECT " + SPALTEN + " FROM erinnerung WHERE chat_id = ? AND zustand = 'OFFEN' "
                + "ORDER BY faellig_um, id", chatId);
    }

    @Override
    public synchronized List<Erinnerung> alleOffenen() {
        return abfragen("SELECT " + SPALTEN + " FROM erinnerung WHERE zustand = 'OFFEN' ORDER BY faellig_um, id");
    }

    /** Löscht ausgelöste Erinnerungen, die vor {@code vor} fällig waren und nie erledigt wurden. */
    synchronized int aufraeumen(Instant vor) {
        return aendern("DELETE FROM erinnerung WHERE zustand = 'AUSGELOEST' AND faellig_um < ?", vor.toEpochMilli());
    }

    @Override
    public synchronized void close() {
        try {
            verbindung.close();
        } catch (SQLException e) {
            throw new SpeicherException("Datenbank konnte nicht geschlossen werden", e);
        }
    }

    // --- Hilfen: Parameter werden immer gebunden, nie in den SQL-Text eingesetzt.

    private int aendern(String sql, Object... parameter) {
        try (PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            binden(anweisung, parameter);
            return anweisung.executeUpdate();
        } catch (SQLException e) {
            throw new SpeicherException("Änderung fehlgeschlagen", e);
        }
    }

    private List<Erinnerung> abfragen(String sql, Object... parameter) {
        try (PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            binden(anweisung, parameter);
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                List<Erinnerung> liste = new ArrayList<>();
                while (ergebnis.next()) {
                    liste.add(new Erinnerung(ergebnis.getLong("id"), ergebnis.getLong("chat_id"),
                            ergebnis.getString("text"), ergebnis.getLong("status_nachricht_id"),
                            Instant.ofEpochMilli(ergebnis.getLong("faellig_um"))));
                }
                return liste;
            }
        } catch (SQLException e) {
            throw new SpeicherException("Abfrage fehlgeschlagen", e);
        }
    }

    private static void binden(PreparedStatement anweisung, Object[] parameter) throws SQLException {
        for (int i = 0; i < parameter.length; i++) {
            anweisung.setObject(i + 1, parameter[i]);
        }
    }
}
