package telegram.lernen.longpolling;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Lösung der Übung: Nachrichten pro Chat zählen, nur {@code message}-Updates anfordern. */
public class UebungLoesung {

    public static void main(String[] args) throws Exception {
        NachrichtenZaehler zaehler = new NachrichtenZaehler();
        new UpdatePoller(BotApi.ausUmgebung(), List.of("message")).laufen(update -> {
            zaehler.zaehle(update);
            System.out.println(zaehler.stand());
        });
    }

    // #region loesung
    static class NachrichtenZaehler {
        private final Map<Long, Integer> proChat = new TreeMap<>();

        void zaehle(JsonNode update) {
            JsonNode nachricht = update.path("message");
            if (!nachricht.isMissingNode()) {
                proChat.merge(nachricht.path("chat").path("id").asLong(), 1, Integer::sum);
            }
        }

        String stand() {
            return proChat.toString();
        }
    }
    // #endregion loesung
}
