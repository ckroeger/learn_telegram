package telegram.lernen.apps.inlinesuche;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KatalogTest {

    private static final Katalog KLEIN = new Katalog(List.of(
            new Eintrag("sendMessage", "Sendet Text."),
            new Eintrag("copyMessage", "Kopiert eine Nachricht."),
            new Eintrag("sendPhoto", "Sendet ein Foto."),
            new Eintrag("getFile", "Liefert den Pfad zu einer Datei."),
            new Eintrag("message", "Genau dieser Name.")));

    private static List<String> namen(List<Eintrag> treffer) {
        return treffer.stream().map(Eintrag::name).toList();
    }

    @Test
    void sortiertErstNachNamenDannNachBeschreibung() {
        // message: genauer Name; sendMessage/copyMessage: Name enthält; getFile enthält "Nachricht" nicht
        assertEquals(List.of("message", "sendMessage", "copyMessage"), namen(KLEIN.suche("message")));
        // "send" steht bei zwei Namen vorn (Stufe "beginnt mit")
        assertEquals(List.of("sendMessage", "sendPhoto"), namen(KLEIN.suche("send")));
        // "datei" steht nur in einer Beschreibung
        assertEquals(List.of("getFile"), namen(KLEIN.suche("datei")));
    }

    @Test
    void beginntMitKommtVorEnthaeltUndNameVorBeschreibung() {
        Katalog katalog = new Katalog(List.of(
                new Eintrag("a", "Foto in der Beschreibung"),
                new Eintrag("xFotox", "enthält"),
                new Eintrag("fotoWeg", "beginnt"),
                new Eintrag("foto", "genau")));

        assertEquals(List.of("foto", "fotoWeg", "xFotox", "a"), namen(katalog.suche("foto")));
    }

    @Test
    void ignoriertGrossUndKleinschreibungUndRaender() {
        assertEquals(namen(KLEIN.suche("sendphoto")), namen(KLEIN.suche("  SENDPHOTO ")));
        assertEquals(List.of("sendPhoto"), namen(KLEIN.suche("SendPhoto")));
    }

    @Test
    void leereOderUnbekannteSucheIstLeer() {
        assertTrue(KLEIN.suche("").isEmpty());
        assertTrue(KLEIN.suche("   ").isEmpty());
        assertTrue(KLEIN.suche("gibtsnicht").isEmpty());
    }

    @Test
    void eintragBautLinkUndNachricht() {
        Eintrag eintrag = new Eintrag("sendMessage", "Sendet Text.");

        assertEquals("https://core.telegram.org/bots/api#sendmessage", eintrag.link());
        assertEquals("sendMessage - Sendet Text.\nhttps://core.telegram.org/bots/api#sendmessage", eintrag.nachricht());
    }

    @Test
    void mitgelieferterKatalogIstGueltigFuerInlineErgebnisse() {
        Katalog katalog = Katalog.ausResource();

        assertTrue(katalog.alle().size() >= 20);
        assertEquals(katalog.alle().size(), katalog.alle().stream().map(Eintrag::name).distinct().count(), "eindeutige IDs");
        for (Eintrag e : katalog.alle()) {
            assertTrue(e.name().matches("[A-Za-z]+"), e.name());
            assertTrue(!e.beschreibung().isBlank() && e.nachricht().length() <= 4096, e.name());
        }
        assertEquals("getMe", katalog.suche("getme").get(0).name());
    }
}
