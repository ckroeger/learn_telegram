package telegram.lernen.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Telegram hat mit {@code "ok":false} geantwortet. */
public class TelegramException extends RuntimeException {

    private final int errorCode;
    private final int retryAfter;

    TelegramException(JsonNode antwort) {
        super("Telegram-Fehler %d: %s".formatted(antwort.path("error_code").asInt(), antwort.path("description").asText()));
        this.errorCode = antwort.path("error_code").asInt();
        // Bei HTTP 429 sagt Telegram, wie viele Sekunden man warten soll.
        this.retryAfter = antwort.path("parameters").path("retry_after").asInt(0);
    }

    public int errorCode() {
        return errorCode;
    }

    public int retryAfter() {
        return retryAfter;
    }
}
