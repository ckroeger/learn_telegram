package telegram.lernen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import telegram.lernen.client.Tastaturen.Taste;

class TastaturenTest {

    @Test
    void inlineBautZeilenMitCallbackUndLink() {
        String json = Tastaturen.inline(List.of(
                List.of(Taste.daten("A", "a"), Taste.daten("B", "b")),
                List.of(Taste.link("Doku", "https://example.com")))).toString();

        assertEquals("{\"inline_keyboard\":[[{\"text\":\"A\",\"callback_data\":\"a\"},"
                + "{\"text\":\"B\",\"callback_data\":\"b\"}],[{\"text\":\"Doku\",\"url\":\"https://example.com\"}]]}", json);
    }

    @Test
    void antwortTastaturPasstSichAnUndHatTextTasten() {
        String json = Tastaturen.antwort(List.of(List.of("/hilfe", "/menue"), List.of("/aus"))).toString();

        assertEquals("{\"keyboard\":[[{\"text\":\"/hilfe\"},{\"text\":\"/menue\"}],[{\"text\":\"/aus\"}]],"
                + "\"resize_keyboard\":true}", json);
    }

    @Test
    void entfernenSetztRemoveKeyboard() {
        assertEquals("{\"remove_keyboard\":true}", Tastaturen.entfernen().toString());
    }

    @Test
    void callbackDataIstAufSechsundsechzigBytesBegrenztNichtZeichen() {
        assertEquals(64, Taste.daten("ok", "x".repeat(64)).callbackData().length());
        assertThrows(IllegalArgumentException.class, () -> Taste.daten("zu lang", "x".repeat(65)));
        // 22 Umlaute sind 22 Zeichen, aber 44 Bytes; 33 sind 66 Bytes und damit zu lang.
        assertEquals(22, Taste.daten("ok", "ä".repeat(22)).callbackData().length());
        assertThrows(IllegalArgumentException.class, () -> Taste.daten("zu lang", "ä".repeat(33)));
    }

    @Test
    void eineTasteBrauchtGenauEineAktion() {
        assertThrows(IllegalArgumentException.class, () -> new Taste("x", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Taste("x", "a", "https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> Taste.daten("", "a"));
        assertThrows(IllegalArgumentException.class, () -> Taste.daten("x", ""));
        assertTrue(Taste.link("ok", "https://example.com").callbackData() == null);
    }
}
