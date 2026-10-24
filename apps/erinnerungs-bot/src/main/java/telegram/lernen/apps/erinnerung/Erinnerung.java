package telegram.lernen.apps.erinnerung;

import java.time.Instant;

/**
 * Eine Erinnerung, wie sie im Speicher steht.
 *
 * @param statusNachrichtId die Nachricht, die gerade den Zustand anzeigt (erst der Status, nach "Später" wieder);
 *                          0 heißt: noch nicht gesendet
 */
record Erinnerung(long id, long chatId, String text, long statusNachrichtId, Instant faelligUm) {
}
