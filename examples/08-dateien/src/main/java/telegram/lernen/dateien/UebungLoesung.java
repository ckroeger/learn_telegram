package telegram.lernen.dateien;

import com.fasterxml.jackson.databind.JsonNode;

/** Lösung der Übung: ein Foto in einem Satz beschreiben. */
public final class UebungLoesung {

    private UebungLoesung() {
    }

    // #region loesung
    /** Zum Beispiel "Foto 1280x720, 85 KB (größte von 3 Größen)". */
    static String fotoBeschreibung(JsonNode groessen) {
        JsonNode groesste = Dateien.groessteFoto(groessen);
        if (groesste == null) {
            return "Kein Foto";
        }
        long kilobyte = Math.round(groesste.path("file_size").asLong() / 1024.0);
        return "Foto %dx%d, %d KB (größte von %d Größen)".formatted(groesste.path("width").asInt(),
                groesste.path("height").asInt(), kilobyte, groessen.size());
    }
    // #endregion loesung
}
