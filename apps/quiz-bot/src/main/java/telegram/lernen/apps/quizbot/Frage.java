package telegram.lernen.apps.quizbot;

import java.util.List;

/** Eine Quizfrage mit genau einer richtigen Antwort. Die Grenzen sind die von {@code sendPoll} im Quiz-Modus. */
public record Frage(String text, List<String> optionen, int richtig, String erklaerung) {

    public Frage {
        if (text.isBlank() || text.length() > 300) {
            throw new IllegalArgumentException("Die Frage braucht 1 bis 300 Zeichen");
        }
        if (optionen.size() < 2 || optionen.size() > 12) {
            throw new IllegalArgumentException("Ein Quiz braucht 2 bis 12 Antworten");
        }
        for (String option : optionen) {
            if (option.isBlank() || option.length() > 100) {
                throw new IllegalArgumentException("Jede Antwort braucht 1 bis 100 Zeichen: " + option);
            }
        }
        if (richtig < 0 || richtig >= optionen.size()) {
            throw new IllegalArgumentException("Die richtige Antwort liegt außerhalb der Antworten");
        }
        if (erklaerung.length() > 200) {
            throw new IllegalArgumentException("Die Erklärung darf höchstens 200 Zeichen haben");
        }
    }
}
