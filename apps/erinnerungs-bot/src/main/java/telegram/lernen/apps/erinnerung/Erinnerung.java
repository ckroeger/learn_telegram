package telegram.lernen.apps.erinnerung;

import java.time.Instant;

/** Eine offene Erinnerung. Lebt nur im Speicher: nach einem Neustart des Bots sind alle weg. */
final class Erinnerung {

    final long id;
    final long chatId;
    final String text;
    /** Die Nachricht, die gerade den Zustand anzeigt (erst der Status, nach "Später" wieder). */
    volatile long statusNachrichtId;
    volatile Instant faelligUm;
    volatile Runnable abbruch = () -> { };

    Erinnerung(long id, long chatId, String text, long statusNachrichtId, Instant faelligUm) {
        this.id = id;
        this.chatId = chatId;
        this.text = text;
        this.statusNachrichtId = statusNachrichtId;
        this.faelligUm = faelligUm;
    }
}
