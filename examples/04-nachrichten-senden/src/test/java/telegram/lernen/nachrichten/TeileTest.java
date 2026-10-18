package telegram.lernen.nachrichten;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TeileTest {

    @Test
    void kurzerTextBleibtEinTeil() {
        assertEquals(List.of("kurz"), Nachrichten.teile("kurz", 4096));
    }

    @Test
    void leererTextErgibtKeineTeile() {
        assertEquals(List.of(), Nachrichten.teile("", 4096));
    }

    @Test
    void genauAmLimitBleibtEinTeil() {
        assertEquals(1, Nachrichten.teile("a".repeat(4096), 4096).size());
    }

    @Test
    void teiltBevorzugtAnZeilenumbruechen() {
        List<String> teile = Nachrichten.teile("aaaa\nbbbb\ncccc", 10);
        assertEquals(List.of("aaaa\nbbbb", "cccc"), teile);
        assertEquals("aaaa\nbbbb\ncccc", String.join("\n", teile));
    }

    @Test
    void teiltHartOhneZeilenumbruch() {
        List<String> teile = Nachrichten.teile("a".repeat(5000), 4096);
        assertEquals(List.of(4096, 904), teile.stream().map(String::length).toList());
    }

    @Test
    void schneidetNieMitteninEinemEmoji() {
        String emoji = "😀";
        String text = "a".repeat(4095) + emoji + "b";
        List<String> teile = Nachrichten.teile(text, 4096);

        assertEquals(List.of("a".repeat(4095), emoji + "b"), teile);
        assertTrue(teile.stream().allMatch(t -> t.length() <= 4096));
    }

    @Test
    void alleTeileBleibenImLimitUndBehaltenDenInhalt() {
        String text = ("Zeile mit etwas Inhalt\n").repeat(1000);
        List<String> teile = Nachrichten.teile(text, 4096);

        assertTrue(teile.size() > 1);
        assertTrue(teile.stream().allMatch(t -> !t.isEmpty() && t.length() <= 4096));
        assertEquals(text.stripTrailing(), String.join("\n", teile).stripTrailing());
    }
}
