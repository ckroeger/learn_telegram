package telegram.lernen.apps.erinnerung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static telegram.lernen.apps.erinnerung.Erinnerungsspeicher.Zustand.AUSGELOEST;
import static telegram.lernen.apps.erinnerung.Erinnerungsspeicher.Zustand.OFFEN;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Was jeder {@link Erinnerungsspeicher} können muss. Die Unterklassen liefern nur den Speicher. */
abstract class ErinnerungsspeicherVertragTest {

    private static final Instant JETZT = Instant.parse("2026-10-24T12:00:00Z");

    abstract Erinnerungsspeicher speicher() throws Exception;

    @Test
    void anlegenVergibtVerschiedeneIdsUndLegtOffenAn() throws Exception {
        Erinnerungsspeicher s = speicher();

        Erinnerung a = s.anlegen(1, "Tee", 100, JETZT);
        Erinnerung b = s.anlegen(1, "Kaffee", 101, JETZT);

        assertTrue(a.id() != b.id());
        assertEquals(a, s.finden(a.id(), OFFEN).orElseThrow());
        assertTrue(s.finden(a.id(), AUSGELOEST).isEmpty());
        assertEquals("Tee", a.text());
        assertEquals(JETZT, a.faelligUm());
    }

    @Test
    void findenKenntKeineUnbekanntenIds() throws Exception {
        assertTrue(speicher().finden(999, OFFEN).isEmpty());
    }

    @Test
    void wechselnGehtNurVomErwartetenZustandUndNurEinmal() throws Exception {
        Erinnerungsspeicher s = speicher();
        long id = s.anlegen(1, "Tee", 100, JETZT).id();

        assertFalse(s.wechseln(id, AUSGELOEST, OFFEN), "falscher Ausgangszustand");
        assertTrue(s.wechseln(id, OFFEN, AUSGELOEST));
        assertFalse(s.wechseln(id, OFFEN, AUSGELOEST), "zweiter Versuch verliert");
        assertTrue(s.finden(id, OFFEN).isEmpty());
        assertTrue(s.finden(id, AUSGELOEST).isPresent());
        assertTrue(s.wechseln(id, AUSGELOEST, OFFEN), "Später: zurück auf OFFEN");
        assertFalse(s.wechseln(12345, OFFEN, AUSGELOEST), "unbekannte ID");
    }

    @Test
    void entfernenGehtNurImErwartetenZustandUndNurEinmal() throws Exception {
        Erinnerungsspeicher s = speicher();
        long id = s.anlegen(1, "Tee", 100, JETZT).id();

        assertFalse(s.entfernen(id, AUSGELOEST));
        assertTrue(s.entfernen(id, OFFEN));
        assertFalse(s.entfernen(id, OFFEN));
        assertTrue(s.finden(id, OFFEN).isEmpty());
    }

    @Test
    void aktualisierenAendertNachrichtUndFaelligkeit() throws Exception {
        Erinnerungsspeicher s = speicher();
        long id = s.anlegen(1, "Tee", 0, JETZT).id();

        s.aktualisieren(id, 555, JETZT.plusSeconds(300));

        Erinnerung e = s.finden(id, OFFEN).orElseThrow();
        assertEquals(555, e.statusNachrichtId());
        assertEquals(JETZT.plusSeconds(300), e.faelligUm());
        assertEquals("Tee", e.text());
    }

    @Test
    void offeneZaehlenUndSortierenProChatNachFaelligkeit() throws Exception {
        Erinnerungsspeicher s = speicher();
        s.anlegen(1, "spät", 1, JETZT.plusSeconds(600));
        s.anlegen(1, "früh", 2, JETZT.plusSeconds(60));
        s.anlegen(2, "fremd", 3, JETZT.plusSeconds(10));
        long ausgeloest = s.anlegen(1, "ausgelöst", 4, JETZT).id();
        s.wechseln(ausgeloest, OFFEN, AUSGELOEST);

        assertEquals(2, s.anzahlOffen(1));
        assertEquals(1, s.anzahlOffen(2));
        assertEquals(0, s.anzahlOffen(3));
        assertEquals(List.of("früh", "spät"), s.offene(1).stream().map(Erinnerung::text).toList());
        assertEquals(List.of("fremd", "früh", "spät"), s.alleOffenen().stream().map(Erinnerung::text).toList());
    }

    @Test
    void textBleibtUnveraendertAuchMitSonderzeichenUndSqlAnfuehrungszeichen() throws Exception {
        Erinnerungsspeicher s = speicher();
        String text = "x'); DROP TABLE erinnerung; -- \"Zitat\" ä € 😀 \n zweite Zeile";

        long id = s.anlegen(1, text, 1, JETZT).id();

        assertEquals(text, s.finden(id, OFFEN).orElseThrow().text());
        assertEquals(1, s.anzahlOffen(1), "die Tabelle ist noch da");
    }

    @Test
    void beiGleichzeitigemWechselnGewinntGenauEiner() throws Exception {
        Erinnerungsspeicher s = speicher();
        long id = s.anlegen(1, "Tee", 100, JETZT).id();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger gewinner = new AtomicInteger();
        List<Thread> alle = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (s.wechseln(id, OFFEN, AUSGELOEST)) {
                        gewinner.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            alle.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : alle) {
            thread.join();
        }

        assertEquals(1, gewinner.get());
    }
}
