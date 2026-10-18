package telegram.lernen.longpolling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BeispieleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void beschreibtNachrichtenMitText() throws Exception {
        JsonNode update = json("{\"update_id\":5,\"message\":{\"chat\":{\"id\":9},\"text\":\"Hallo\"}}");
        assertEquals("Update 5 (message): Hallo", PollingBot.beschreibe(update));
    }

    @Test
    void beschreibtUpdatesOhneText() throws Exception {
        JsonNode update = json("{\"update_id\":6,\"callback_query\":{\"id\":\"1\",\"data\":\"x\"}}");
        assertEquals("Update 6 (callback_query): (kein Text)", PollingBot.beschreibe(update));
    }

    @Test
    void zaehlerZaehltNurNachrichtenProChat() throws Exception {
        UebungLoesung.NachrichtenZaehler zaehler = new UebungLoesung.NachrichtenZaehler();
        zaehler.zaehle(json("{\"update_id\":1,\"message\":{\"chat\":{\"id\":2},\"text\":\"a\"}}"));
        zaehler.zaehle(json("{\"update_id\":2,\"message\":{\"chat\":{\"id\":2},\"text\":\"b\"}}"));
        zaehler.zaehle(json("{\"update_id\":3,\"message\":{\"chat\":{\"id\":1},\"text\":\"c\"}}"));
        zaehler.zaehle(json("{\"update_id\":4,\"edited_message\":{\"chat\":{\"id\":1},\"text\":\"d\"}}"));
        assertEquals("{1=1, 2=2}", zaehler.stand());
    }
}
