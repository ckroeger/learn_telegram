package telegram.lernen.apps.inlinesuche;

import java.util.Locale;

/** Ein Eintrag des Katalogs: eine Methode der Bot API mit einer kurzen Beschreibung. */
public record Eintrag(String name, String beschreibung) {

    /** Die Adresse in der Doku: der Anker ist der Methodenname in Kleinbuchstaben. */
    public String link() {
        return "https://core.telegram.org/bots/api#" + name.toLowerCase(Locale.ROOT);
    }

    /** Der Text, der beim Auswählen in den Chat gesendet wird. */
    public String nachricht() {
        return name + " - " + beschreibung + "\n" + link();
    }
}
