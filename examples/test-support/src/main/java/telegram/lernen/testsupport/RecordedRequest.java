package telegram.lernen.testsupport;

/**
 * Ein Aufruf, den der Fake empfangen hat. {@code query} und {@code body} sind leer, wenn nichts gesendet wurde.
 * {@code bodyBytes} sind die rohen Bytes, wichtig bei Datei-Uploads, die kein gültiger Text sind.
 * Für Downloads ist {@code method} gleich {@code "file"} und {@code body} der angefragte Dateipfad.
 */
public record RecordedRequest(String method, String token, String query, String body, byte[] bodyBytes) {

    public boolean bodyContains(String teil) {
        return body.contains(teil) || query.contains(teil);
    }
}
