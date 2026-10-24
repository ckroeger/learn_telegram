package telegram.lernen.apps.erinnerung;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dauern wie {@code 30s}, {@code 10m}, {@code 2h} oder {@code 1d} lesen und lesbar ausgeben. */
final class Dauer {

    static final Duration MAXIMUM = Duration.ofDays(7);

    private static final Pattern MUSTER = Pattern.compile("(\\d{1,6})([smhd])");

    private Dauer() {
    }

    // #region lesen
    /** Liefert nichts, wenn die Eingabe nicht passt oder außerhalb von 1 Sekunde bis 7 Tagen liegt. */
    static Optional<Duration> lesen(String eingabe) {
        Matcher treffer = MUSTER.matcher(eingabe.toLowerCase());
        if (!treffer.matches()) {
            return Optional.empty();
        }
        long zahl = Long.parseLong(treffer.group(1));
        Duration dauer = switch (treffer.group(2)) {
            case "s" -> Duration.ofSeconds(zahl);
            case "m" -> Duration.ofMinutes(zahl);
            case "h" -> Duration.ofHours(zahl);
            default -> Duration.ofDays(zahl);
        };
        return dauer.isZero() || dauer.compareTo(MAXIMUM) > 0 ? Optional.empty() : Optional.of(dauer);
    }
    // #endregion lesen

    /** Die größte Einheit, in der die Dauer ganzzahlig aufgeht: "90 Sekunden", "2 Minuten", "1 Tag". */
    static String text(Duration dauer) {
        long sekunden = dauer.toSeconds();
        if (sekunden % 86400 == 0 && sekunden > 0) {
            return einheit(sekunden / 86400, "Tag", "Tage");
        } else if (sekunden % 3600 == 0 && sekunden > 0) {
            return einheit(sekunden / 3600, "Stunde", "Stunden");
        } else if (sekunden % 60 == 0 && sekunden > 0) {
            return einheit(sekunden / 60, "Minute", "Minuten");
        }
        return einheit(sekunden, "Sekunde", "Sekunden");
    }

    private static String einheit(long anzahl, String einzahl, String mehrzahl) {
        return anzahl + " " + (anzahl == 1 ? einzahl : mehrzahl);
    }
}
