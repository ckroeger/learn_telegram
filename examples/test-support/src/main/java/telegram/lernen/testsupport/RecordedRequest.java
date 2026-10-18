package telegram.lernen.testsupport;

/** Ein Aufruf, den der Fake empfangen hat. {@code query} und {@code body} sind leer, wenn nichts gesendet wurde. */
public record RecordedRequest(String method, String token, String query, String body) {

    public boolean bodyContains(String teil) {
        return body.contains(teil) || query.contains(teil);
    }
}
