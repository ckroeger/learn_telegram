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

    @Test
    void inlineTastenSetzenSwitchInlineQueryUndErlaubenEineLeereAbfrage() {
        var markup = Tastaturen.inline(java.util.List.of(java.util.List.of(
                Taste.inlineInAnderemChat("Teilen", ""), Taste.inlineHier("Hier suchen", "sendMessage"))));

        var tasten = markup.get("inline_keyboard").get(0);
        assertEquals("", tasten.get(0).get("switch_inline_query").asText());
        assertTrue(tasten.get(0).has("switch_inline_query") && !tasten.get(0).has("callback_data"));
        assertEquals("sendMessage", tasten.get(1).get("switch_inline_query_current_chat").asText());
        assertThrows(IllegalArgumentException.class, () -> new Taste("x", "a", null, "", null));
        assertThrows(IllegalArgumentException.class, () -> new Taste("x", null, null, "a", "b"));
    }

    @Test
    void webAppTastenBrauchenHttpsUndSindEineEigeneAktion() {
        var markup = Tastaturen.inline(java.util.List.of(java.util.List.of(Taste.webApp("Öffnen", "https://beispiel.example/app"))));
        var knopf = markup.get("inline_keyboard").get(0).get(0);
        assertEquals("https://beispiel.example/app", knopf.at("/web_app/url").asText());
        assertTrue(!knopf.has("url") && !knopf.has("callback_data"));
        assertThrows(IllegalArgumentException.class, () -> Taste.webApp("x", "http://beispiel.example"));
        assertThrows(IllegalArgumentException.class, () -> new Taste("x", "a", null, null, null, "https://b.example"));
    }

    @Test
    void antwortTastaturMitWebApp() {
        var markup = Tastaturen.antwortMitWebApp("Formular", "https://beispiel.example/app");

        assertEquals("Formular", markup.at("/keyboard/0/0/text").asText());
        assertEquals("https://beispiel.example/app", markup.at("/keyboard/0/0/web_app/url").asText());
        assertTrue(markup.get("resize_keyboard").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> Tastaturen.antwortMitWebApp("x", "http://beispiel.example"));
    }
}
