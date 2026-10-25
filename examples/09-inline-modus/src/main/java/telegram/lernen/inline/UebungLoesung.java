package telegram.lernen.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import telegram.lernen.client.Inline;
import telegram.lernen.client.TelegramClient;
import telegram.lernen.client.UpdatePoller;

/** Lösung der Übung: ein Inline-Rechner. {@code @dein_bot 12+30} liefert das Ergebnis "12 + 30 = 42". */
public final class UebungLoesung {

    private UebungLoesung() {
    }

    // #region loesung
    private static final Pattern AUFGABE =
            Pattern.compile("(-?\\d{1,15}(?:\\.\\d{1,15})?)\\s*([-+*/])\\s*(-?\\d{1,15}(?:\\.\\d{1,15})?)");

    /** Rechnet "12+30" aus. Leer, wenn die Eingabe keine Aufgabe ist oder durch 0 geteilt wird. */
    static Optional<String> rechnen(String eingabe) {
        Matcher m = AUFGABE.matcher(eingabe.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        BigDecimal a = new BigDecimal(m.group(1));
        BigDecimal b = new BigDecimal(m.group(3));
        BigDecimal ergebnis;
        switch (m.group(2)) {
            case "+" -> ergebnis = a.add(b);
            case "-" -> ergebnis = a.subtract(b);
            case "*" -> ergebnis = a.multiply(b);
            default -> {
                if (b.signum() == 0) {
                    return Optional.empty();
                }
                ergebnis = a.divide(b, MathContext.DECIMAL64);
            }
        }
        String zahl = ergebnis.signum() == 0 ? "0" : ergebnis.stripTrailingZeros().toPlainString();
        return Optional.of(m.group(1) + " " + m.group(2) + " " + m.group(3) + " = " + zahl);
    }

    static void antworten(TelegramClient client, JsonNode abfrage) throws IOException, InterruptedException {
        List<ObjectNode> ergebnisse = rechnen(abfrage.path("query").asText())
                .map(text -> List.of(Inline.artikel("ergebnis", text, "Antippen sendet die Rechnung in den Chat", text)))
                .orElse(List.of());
        ObjectNode antwort = Inline.antwort(abfrage.get("id").asText(), ergebnisse);
        antwort.put("cache_time", 5);
        client.aufrufen("answerInlineQuery", antwort);
    }
    // #endregion loesung

    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        System.out.println("Inline-Rechner läuft, Strg+C beendet");
        new UpdatePoller(client, List.of("inline_query")).laufen(update -> {
            try {
                if (update.has("inline_query")) {
                    antworten(client, update.get("inline_query"));
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
