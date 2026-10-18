package telegram.lernen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HalloTest {

    @Test
    void gruesstFreundlich() {
        assertEquals("Hallo Telegram", Hallo.gruss());
    }

    @Test
    void loesungGruesstMitNamen() {
        assertEquals("Hallo Telegram, Welt!", UebungLoesung.grussFuer("Welt"));
    }
}
