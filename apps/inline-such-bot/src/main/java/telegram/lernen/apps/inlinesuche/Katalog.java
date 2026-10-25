package telegram.lernen.apps.inlinesuche;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Die lokale Datenquelle des Such-Bots: eine Liste von Einträgen, durchsuchbar nach Name und Beschreibung. */
public final class Katalog {

    private final List<Eintrag> eintraege;

    public Katalog(List<Eintrag> eintraege) {
        this.eintraege = List.copyOf(eintraege);
    }

    /** Liest {@code katalog.tsv} aus dem Klassenpfad: pro Zeile Name, Tabulator, Beschreibung. */
    public static Katalog ausResource() {
        try (InputStream eingang = Katalog.class.getResourceAsStream("/katalog.tsv")) {
            if (eingang == null) {
                throw new IllegalStateException("katalog.tsv fehlt im Klassenpfad");
            }
            List<Eintrag> liste = new ArrayList<>();
            BufferedReader leser = new BufferedReader(new InputStreamReader(eingang, StandardCharsets.UTF_8));
            for (String zeile = leser.readLine(); zeile != null; zeile = leser.readLine()) {
                String[] teile = zeile.split("\t", 2);
                if (teile.length == 2 && !teile[0].isBlank()) {
                    liste.add(new Eintrag(teile[0].strip(), teile[1].strip()));
                }
            }
            return new Katalog(liste);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Eintrag> alle() {
        return eintraege;
    }

    // #region suche
    /**
     * Sucht ohne Beachtung der Groß- und Kleinschreibung. Die Reihenfolge: genauer Name, Name beginnt mit dem Suchwort,
     * Name enthält es, nur die Beschreibung enthält es. Innerhalb einer Stufe bleibt die Reihenfolge des Katalogs.
     */
    public List<Eintrag> suche(String suchwort) {
        String wort = suchwort.strip().toLowerCase(Locale.ROOT);
        if (wort.isEmpty()) {
            return List.of();
        }
        return eintraege.stream()
                .filter(e -> stufe(e, wort) < 4)
                .sorted(Comparator.comparingInt(e -> stufe(e, wort)))
                .toList();
    }

    private static int stufe(Eintrag eintrag, String wort) {
        String name = eintrag.name().toLowerCase(Locale.ROOT);
        if (name.equals(wort)) {
            return 0;
        }
        if (name.startsWith(wort)) {
            return 1;
        }
        if (name.contains(wort)) {
            return 2;
        }
        return eintrag.beschreibung().toLowerCase(Locale.ROOT).contains(wort) ? 3 : 4;
    }
    // #endregion suche
}
