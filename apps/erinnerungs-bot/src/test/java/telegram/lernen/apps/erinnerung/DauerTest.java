package telegram.lernen.apps.erinnerung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DauerTest {

    @Test
    void liestAlleEinheiten() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), Dauer.lesen("30s"));
        assertEquals(Optional.of(Duration.ofMinutes(10)), Dauer.lesen("10m"));
        assertEquals(Optional.of(Duration.ofHours(2)), Dauer.lesen("2h"));
        assertEquals(Optional.of(Duration.ofDays(1)), Dauer.lesen("1d"));
        assertEquals(Optional.of(Duration.ofMinutes(5)), Dauer.lesen("5M"), "Groß- und Kleinschreibung egal");
    }

    @Test
    void grenzenSindEineSekundeBisSiebenTage() {
        assertTrue(Dauer.lesen("1s").isPresent());
        assertTrue(Dauer.lesen("7d").isPresent());
        assertTrue(Dauer.lesen("168h").isPresent());
        assertTrue(Dauer.lesen("0s").isEmpty());
        assertTrue(Dauer.lesen("8d").isEmpty());
        assertTrue(Dauer.lesen("169h").isEmpty());
        assertTrue(Dauer.lesen("999999d").isEmpty());
    }

    @Test
    void ungueltigeEingabenGebenNichts() {
        for (String eingabe : new String[] {"", "10", "m10", "10x", "1.5h", "-5m", "10 m", "ten", "10mm", "9999999s"}) {
            assertTrue(Dauer.lesen(eingabe).isEmpty(), eingabe);
        }
    }

    @Test
    void textNutztDieGroessteGanzzahligeEinheit() {
        assertEquals("1 Sekunde", Dauer.text(Duration.ofSeconds(1)));
        assertEquals("90 Sekunden", Dauer.text(Duration.ofSeconds(90)));
        assertEquals("1 Minute", Dauer.text(Duration.ofSeconds(60)));
        assertEquals("10 Minuten", Dauer.text(Duration.ofMinutes(10)));
        assertEquals("1 Stunde", Dauer.text(Duration.ofHours(1)));
        assertEquals("36 Stunden", Dauer.text(Duration.ofHours(36)));
        assertEquals("2 Tage", Dauer.text(Duration.ofDays(2)));
    }
}
