package telegram.lernen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class InlineTest {

    @Test
    void artikelHatTypIdTitelUndNachrichtentext() {
        ObjectNode artikel = Inline.artikel("getMe", "getMe", "Infos zum Bot", "getMe - Infos zum Bot");

        assertEquals("article", artikel.get("type").asText());
        assertEquals("getMe", artikel.get("id").asText());
        assertEquals("getMe", artikel.get("title").asText());
        assertEquals("Infos zum Bot", artikel.get("description").asText());
        assertEquals("getMe - Infos zum Bot", artikel.at("/input_message_content/message_text").asText());
    }

    @Test
    void leereBeschreibungWirdWeggelassen() {
        assertFalse(Inline.artikel("a", "t", "", "x").has("description"));
        assertFalse(Inline.artikel("a", "t", null, "x").has("description"));
    }

    @Test
    void idIstInBytesBegrenztNichtInZeichen() {
        Inline.artikel("x".repeat(64), "t", null, "x");
        Inline.artikel("ä".repeat(32), "t", null, "x");
        assertThrows(IllegalArgumentException.class, () -> Inline.artikel("x".repeat(65), "t", null, "x"));
        assertThrows(IllegalArgumentException.class, () -> Inline.artikel("ä".repeat(33), "t", null, "x"));
        assertThrows(IllegalArgumentException.class, () -> Inline.artikel("", "t", null, "x"));
    }

    @Test
    void nachrichtentextBrauchtEinBisVierTausendNeunzigSechsZeichen() {
        Inline.artikel("a", "t", null, "x".repeat(4096));
        assertThrows(IllegalArgumentException.class, () -> Inline.artikel("a", "t", null, ""));
        assertThrows(IllegalArgumentException.class, () -> Inline.artikel("a", "t", null, "x".repeat(4097)));
    }

    @Test
    void antwortNimmtHoechstensFuenfzigErgebnisse() {
        ObjectNode eins = Inline.artikel("a", "t", null, "x");

        assertEquals(50, Inline.antwort("q1", Collections.nCopies(50, eins)).get("results").size());
        assertEquals("q1", Inline.antwort("q1", List.of(eins)).get("inline_query_id").asText());
        assertThrows(IllegalArgumentException.class, () -> Inline.antwort("q1", Collections.nCopies(51, eins)));
    }

    @Test
    void antwortOhneErgebnisseIstErlaubt() {
        assertEquals(0, Inline.antwort("q1", List.of()).get("results").size());
    }
}
