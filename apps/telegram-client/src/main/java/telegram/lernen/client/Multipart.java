package telegram.lernen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Baut den Körper einer {@code multipart/form-data}-Anfrage, so wie ein Browser ein Formular mit Datei sendet. */
final class Multipart {

    private static final String ZEILENENDE = "\r\n";

    private final String grenze = "----telegram-lernen-" + UUID.randomUUID();
    private final List<BodyPublisher> teile = new ArrayList<>();

    String contentType() {
        return "multipart/form-data; boundary=" + grenze;
    }

    // #region multipart
    /** Parameter als Textfelder, Dateien als Teile mit Dateiname. Dateien werden gestreamt, nicht geladen. */
    static Multipart aus(ObjectNode parameter, List<Dateianhang> dateien) throws IOException {
        Multipart multipart = new Multipart();
        for (Iterator<Map.Entry<String, JsonNode>> felder = parameter.fields(); felder.hasNext(); ) {
            Map.Entry<String, JsonNode> feld = felder.next();
            // Zahlen und Wahrheitswerte als Text, Objekte und Listen (z. B. reply_markup) als JSON-Text.
            JsonNode wert = feld.getValue();
            multipart.text(feld.getKey(), wert.isValueNode() ? wert.asText() : wert.toString());
        }
        for (Dateianhang datei : dateien) {
            multipart.datei(datei);
        }
        multipart.teile.add(BodyPublishers.ofString("--" + multipart.grenze + "--" + ZEILENENDE));
        return multipart;
    }
    // #endregion multipart

    BodyPublisher koerper() {
        return BodyPublishers.concat(teile.toArray(new BodyPublisher[0]));
    }

    private void text(String name, String wert) {
        teile.add(BodyPublishers.ofString("--" + grenze + ZEILENENDE
                + "Content-Disposition: form-data; name=\"" + maskieren(name) + "\"" + ZEILENENDE + ZEILENENDE
                + wert + ZEILENENDE));
    }

    private void datei(Dateianhang datei) throws IOException {
        teile.add(BodyPublishers.ofString("--" + grenze + ZEILENENDE
                + "Content-Disposition: form-data; name=\"" + maskieren(datei.feld()) + "\"; filename=\""
                + maskieren(datei.dateiname()) + "\"" + ZEILENENDE
                + "Content-Type: " + datei.contentType() + ZEILENENDE + ZEILENENDE));
        teile.add(datei.pfad() != null
                ? BodyPublishers.ofFile(datei.pfad())
                : BodyPublishers.ofByteArray(datei.inhalt()));
        teile.add(BodyPublishers.ofString(ZEILENENDE));
    }

    /**
     * Schützt Namen in den Kopfzeilen: Anführungszeichen und Zeilenumbrüche im Dateinamen würden sonst den Aufbau
     * der Anfrage verändern (Header-Injection). Das ist die Schreibweise aus RFC 7578.
     */
    static String maskieren(String name) {
        return name.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }
}
