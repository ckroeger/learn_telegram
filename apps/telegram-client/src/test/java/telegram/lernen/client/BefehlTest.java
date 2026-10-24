package telegram.lernen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BefehlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode nachricht(String text, int befehlsLaenge) throws Exception {
        String entities = befehlsLaenge > 0
                ? ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":" + befehlsLaenge + "}]" : "";
        return JSON.readTree("{\"text\":" + JSON.writeValueAsString(text) + entities + "}");
    }

    @Test
    void liestNameUndArgumente() throws Exception {
        Optional<Befehl> befehl = Befehl.aus(nachricht("/echo  Hallo Welt ", 5), "mein_bot");
        assertEquals(new Befehl("echo", "Hallo Welt"), befehl.orElseThrow());
    }

    @Test
    void befehlOhneArgumenteHatLeereArgumente() throws Exception {
        assertEquals("", Befehl.aus(nachricht("/hilfe", 6), "mein_bot").orElseThrow().argumente());
    }

    @Test
    void befehlAnDenEigenenBotMitAtWirdErkanntOhneGrossKleinUnterschied() throws Exception {
        Befehl befehl = Befehl.aus(nachricht("/Hilfe@Mein_Bot x", 15), "mein_bot").orElseThrow();
        assertEquals("hilfe", befehl.name());
        assertEquals("x", befehl.argumente());
    }

    @Test
    void befehlAnEinenAnderenBotIstKeinBefehlFuerUnsAberTrotzdemEiner() throws Exception {
        JsonNode nachricht = nachricht("/hilfe@anderer_bot", 18);
        assertTrue(Befehl.aus(nachricht, "mein_bot").isEmpty());
        assertTrue(Befehl.beginntMitBefehl(nachricht));
    }

    @Test
    void normalerTextUndEinSchraegstrichMittenImTextSindKeineBefehle() throws Exception {
        assertFalse(Befehl.beginntMitBefehl(nachricht("Hallo /hilfe", 0)));
        assertTrue(Befehl.aus(nachricht("Hallo /hilfe", 0), "mein_bot").isEmpty());
        assertTrue(Befehl.aus(nachricht("/ohne entity", 0), "mein_bot").isEmpty());
    }
}
