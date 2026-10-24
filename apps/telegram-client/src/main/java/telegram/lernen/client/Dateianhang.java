package telegram.lernen.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Eine Datei, die per {@code multipart/form-data} hochgeladen wird.
 *
 * @param feld         der Parametername der Methode, z. B. {@code document} oder {@code photo}
 * @param dateiname    der Name, unter dem Telegram die Datei anzeigt
 * @param contentType  der MIME-Typ, z. B. {@code application/pdf}
 */
public record Dateianhang(String feld, String dateiname, String contentType, Path pfad, byte[] inhalt) {

    public Dateianhang {
        if (feld == null || feld.isBlank() || dateiname == null || dateiname.isBlank()) {
            throw new IllegalArgumentException("Feld und Dateiname sind Pflicht");
        }
        if ((pfad == null) == (inhalt == null)) {
            throw new IllegalArgumentException("Genau eines von pfad und inhalt muss gesetzt sein");
        }
    }

    /** Eine Datei von der Festplatte. Sie wird beim Senden gelesen, nicht vorher in den Speicher geladen. */
    public static Dateianhang von(String feld, Path pfad) throws IOException {
        String typ = Files.probeContentType(pfad);
        return new Dateianhang(feld, pfad.getFileName().toString(), typ == null ? "application/octet-stream" : typ,
                pfad, null);
    }

    /** Eine Datei aus Bytes im Speicher, etwa ein erzeugter Bericht. */
    public static Dateianhang von(String feld, String dateiname, String contentType, byte[] inhalt) {
        return new Dateianhang(feld, dateiname, contentType, null, inhalt.clone());
    }

    @Override
    public boolean equals(Object anderes) {
        return anderes instanceof Dateianhang d && feld.equals(d.feld) && dateiname.equals(d.dateiname)
                && contentType.equals(d.contentType) && java.util.Objects.equals(pfad, d.pfad)
                && Arrays.equals(inhalt, d.inhalt);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(feld, dateiname, contentType, pfad) * 31 + Arrays.hashCode(inhalt);
    }
}
