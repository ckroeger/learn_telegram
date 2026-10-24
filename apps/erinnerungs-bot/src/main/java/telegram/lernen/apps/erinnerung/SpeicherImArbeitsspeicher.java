package telegram.lernen.apps.erinnerung;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Erinnerungen nur im Speicher: nach dem Beenden des Programms sind sie weg. */
final class SpeicherImArbeitsspeicher implements Erinnerungsspeicher {

    private record Zeile(Erinnerung erinnerung, Zustand zustand) { }

    private final Map<Long, Zeile> zeilen = new HashMap<>();
    private long naechsteId = 1;

    @Override
    public synchronized Erinnerung anlegen(long chatId, String text, long statusNachrichtId, Instant faelligUm) {
        Erinnerung erinnerung = new Erinnerung(naechsteId++, chatId, text, statusNachrichtId, faelligUm);
        zeilen.put(erinnerung.id(), new Zeile(erinnerung, Zustand.OFFEN));
        return erinnerung;
    }

    @Override
    public synchronized Optional<Erinnerung> finden(long id, Zustand zustand) {
        Zeile zeile = zeilen.get(id);
        return zeile != null && zeile.zustand() == zustand ? Optional.of(zeile.erinnerung()) : Optional.empty();
    }

    @Override
    public synchronized boolean wechseln(long id, Zustand von, Zustand nach) {
        Zeile zeile = zeilen.get(id);
        if (zeile == null || zeile.zustand() != von) {
            return false;
        }
        zeilen.put(id, new Zeile(zeile.erinnerung(), nach));
        return true;
    }

    @Override
    public synchronized boolean entfernen(long id, Zustand zustand) {
        Zeile zeile = zeilen.get(id);
        if (zeile == null || zeile.zustand() != zustand) {
            return false;
        }
        zeilen.remove(id);
        return true;
    }

    @Override
    public synchronized void aktualisieren(long id, long statusNachrichtId, Instant faelligUm) {
        Zeile zeile = zeilen.get(id);
        if (zeile != null) {
            Erinnerung e = zeile.erinnerung();
            zeilen.put(id, new Zeile(new Erinnerung(e.id(), e.chatId(), e.text(), statusNachrichtId, faelligUm),
                    zeile.zustand()));
        }
    }

    @Override
    public synchronized long anzahlOffen(long chatId) {
        return offene(chatId).size();
    }

    @Override
    public synchronized List<Erinnerung> offene(long chatId) {
        return alleOffenen().stream().filter(e -> e.chatId() == chatId).toList();
    }

    @Override
    public synchronized List<Erinnerung> alleOffenen() {
        List<Erinnerung> offene = new ArrayList<>();
        for (Zeile zeile : zeilen.values()) {
            if (zeile.zustand() == Zustand.OFFEN) {
                offene.add(zeile.erinnerung());
            }
        }
        offene.sort(Comparator.comparing(Erinnerung::faelligUm).thenComparingLong(Erinnerung::id));
        return offene;
    }
}
