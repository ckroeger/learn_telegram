package telegram.lernen.apps.erinnerung;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Wo die Erinnerungen liegen. Der Bot kennt nur diese Schnittstelle, deshalb lässt sich der Speicher austauschen:
 * {@link SpeicherImArbeitsspeicher} vergisst beim Beenden alles, {@link SqliteSpeicher} behält die Erinnerungen.
 *
 * <p>Der Zustand einer Erinnerung wechselt nur in eine Richtung, außer bei "Später":
 * {@code OFFEN} (Timer läuft) → {@code AUSGELOEST} (Erinnerung gesendet, wartet auf "Erledigt" oder "Später").
 * Die Wechsel sind atomar und melden, ob sie gewonnen haben. So entscheidet der Speicher, wer gewinnt,
 * wenn "Abbrechen" und Fälligkeit gleichzeitig kommen.
 */
interface Erinnerungsspeicher {

    enum Zustand { OFFEN, AUSGELOEST }

    // #region schnittstelle
    /** Legt eine Erinnerung im Zustand OFFEN an und vergibt ihre ID. */
    Erinnerung anlegen(long chatId, String text, long statusNachrichtId, Instant faelligUm);

    Optional<Erinnerung> finden(long id, Zustand zustand);

    /** Wechselt den Zustand, wenn die Erinnerung gerade {@code von} hat. Genau ein gleichzeitiger Aufruf gewinnt. */
    boolean wechseln(long id, Zustand von, Zustand nach);

    /** Löscht die Erinnerung, wenn sie im Zustand ist. Genau ein gleichzeitiger Aufruf gewinnt. */
    boolean entfernen(long id, Zustand zustand);

    /** Setzt die anzeigende Nachricht und die Fälligkeit neu ("Später" und nach dem Senden des Status). */
    void aktualisieren(long id, long statusNachrichtId, Instant faelligUm);

    long anzahlOffen(long chatId);

    /** Die offenen Erinnerungen eines Chats, früheste Fälligkeit zuerst. */
    List<Erinnerung> offene(long chatId);

    /** Alle offenen Erinnerungen aller Chats, zum Wiederaufnehmen nach einem Neustart. */
    List<Erinnerung> alleOffenen();
    // #endregion schnittstelle
}
