package telegram.lernen.apps.quizbot;

import java.util.List;

/** Die Fragen des Beispiels: Wissen aus den Lektionen dieser Seite. */
final class Fragen {

    private Fragen() {
    }

    static final List<Frage> STANDARD = List.of(
            new Frage("Wie viele Bytes darf callback_data einer Inline-Taste höchstens haben?",
                    List.of("16", "64", "128", "256"), 1,
                    "Es sind 1 bis 64 Bytes, keine Zeichen: ein ä zählt doppelt."),
            new Frage("Welcher Parameter von getUpdates bestätigt bereits verarbeitete Updates?",
                    List.of("limit", "timeout", "offset", "confirm"), 2,
                    "offset = update_id + 1 bestätigt dieses und alle früheren Updates."),
            new Frage("Mit welchem HTTP-Status antwortet die Bot API, wenn du zu schnell sendest?",
                    List.of("401", "404", "429", "503"), 2,
                    "Bei 429 steht in parameters.retry_after, wie viele Sekunden du warten musst."),
            new Frage("Bis zu welcher Größe kann ein Bot Dateien mit getFile herunterladen?",
                    List.of("1 MB", "5 MB", "20 MB", "2 GB"), 2,
                    "Download: bis 20 MB. Hochladen darf ein Bot per Standard-API bis 50 MB."),
            new Frage("Welches Update kommt an, wenn jemand auf eine Inline-Taste mit callback_data tippt?",
                    List.of("callback_query", "inline_query", "message", "poll_answer"), 0,
                    "Beantworte es immer mit answerCallbackQuery, sonst dreht sich der Ladebalken weiter."));
}
