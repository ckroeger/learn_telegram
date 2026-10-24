package telegram.lernen.apps.erinnerung;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Führt eine Aufgabe nach einer Wartezeit aus. Als Schnittstelle, damit Tests die Zeit selbst steuern. */
interface Planer {

    /** Plant die Aufgabe ein und liefert eine Möglichkeit, sie wieder abzusagen. */
    Runnable planen(Duration verzoegerung, Runnable aufgabe);

    // #region echt
    /** Ein Thread, der die Aufgaben zur rechten Zeit ausführt. Daemon: er hält das Programm nicht am Leben. */
    static Planer echt() {
        ScheduledExecutorService ausfuehrer = Executors.newSingleThreadScheduledExecutor(aufgabe -> {
            Thread thread = new Thread(aufgabe, "erinnerungen");
            thread.setDaemon(true);
            return thread;
        });
        return (verzoegerung, aufgabe) -> {
            ScheduledFuture<?> geplant = ausfuehrer.schedule(aufgabe, verzoegerung.toMillis(), TimeUnit.MILLISECONDS);
            return () -> geplant.cancel(false);
        };
    }
    // #endregion echt
}
