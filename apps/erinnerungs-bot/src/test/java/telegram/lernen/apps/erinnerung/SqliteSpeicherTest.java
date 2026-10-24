package telegram.lernen.apps.erinnerung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static telegram.lernen.apps.erinnerung.Erinnerungsspeicher.Zustand.AUSGELOEST;
import static telegram.lernen.apps.erinnerung.Erinnerungsspeicher.Zustand.OFFEN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der gemeinsame Vertrag, jetzt gegen eine echte SQLite-Datei, dazu das, was nur SQLite können muss. */
class SqliteSpeicherTest extends ErinnerungsspeicherVertragTest {

    private static final Instant JETZT = Instant.parse("2026-10-24T12:00:00Z");

    @TempDir
    Path ordner;

    private final List<SqliteSpeicher> offen = new ArrayList<>();

    @Override
    Erinnerungsspeicher speicher() {
        return oeffnen();
    }

    private SqliteSpeicher oeffnen() {
        SqliteSpeicher speicher = SqliteSpeicher.oeffnen(ordner.resolve("test.db").toString());
        offen.add(speicher);
        return speicher;
    }

    @AfterEach
    void schliessen() {
        offen.forEach(SqliteSpeicher::close);
    }

    @Test
    void erinnerungenUeberlebenDasSchliessenUndOeffnenDerDatei() {
        SqliteSpeicher erster = oeffnen();
        long offenId = erster.anlegen(1, "bleibt offen", 10, JETZT.plusSeconds(60)).id();
        long ausgeloestId = erster.anlegen(1, "ausgelöst", 11, JETZT).id();
        erster.wechseln(ausgeloestId, OFFEN, AUSGELOEST);
        erster.close();

        SqliteSpeicher zweiter = oeffnen();

        assertEquals("bleibt offen", zweiter.finden(offenId, OFFEN).orElseThrow().text());
        assertEquals(JETZT.plusSeconds(60), zweiter.finden(offenId, OFFEN).orElseThrow().faelligUm());
        assertTrue(zweiter.finden(ausgeloestId, AUSGELOEST).isPresent(), "auch der Zustand bleibt");
        assertEquals(1, zweiter.anzahlOffen(1));
    }

    @Test
    void idsWerdenNachDemNeuoeffnenWeitergezaehltUndNieWiederverwendet() {
        SqliteSpeicher erster = oeffnen();
        long a = erster.anlegen(1, "a", 1, JETZT).id();
        erster.entfernen(a, OFFEN);
        erster.close();

        long b = oeffnen().anlegen(1, "b", 1, JETZT).id();

        assertTrue(b > a, "eine alte Taste darf nie eine neue Erinnerung treffen");
    }

    @Test
    void zweiVerbindungenAufDieselbeDateiSehenDieselbenDaten() {
        SqliteSpeicher a = oeffnen();
        SqliteSpeicher b = oeffnen();

        long id = a.anlegen(1, "geteilt", 1, JETZT).id();

        assertTrue(b.finden(id, OFFEN).isPresent(), "WAL: ein zweiter Leser sieht committete Daten");
    }

    @Test
    void aufraeumenLoeschtNurAlteAusgeloesteErinnerungen() {
        SqliteSpeicher s = oeffnen();
        long alt = s.anlegen(1, "alt", 1, JETZT.minusSeconds(100)).id();
        long neu = s.anlegen(1, "neu", 2, JETZT).id();
        long offenAlt = s.anlegen(1, "offen und alt", 3, JETZT.minusSeconds(100)).id();
        s.wechseln(alt, OFFEN, AUSGELOEST);
        s.wechseln(neu, OFFEN, AUSGELOEST);

        int geloescht = s.aufraeumen(JETZT.minusSeconds(50));

        assertEquals(1, geloescht);
        assertTrue(s.finden(alt, AUSGELOEST).isEmpty());
        assertTrue(s.finden(neu, AUSGELOEST).isPresent());
        assertTrue(s.finden(offenAlt, OFFEN).isPresent(), "offene Erinnerungen bleiben immer");
    }

    @Test
    void dasSchemaWirdBeimOeffnenEinerVorhandenenDateiNichtZerstoert() throws Exception {
        oeffnen().anlegen(1, "vorhanden", 1, JETZT);
        offen.get(0).close();

        oeffnen(); // zweites Öffnen führt CREATE TABLE IF NOT EXISTS erneut aus

        assertEquals(1, offen.get(1).alleOffenen().size());
        assertTrue(Files.exists(ordner.resolve("test.db")));
    }
}
