package telegram.lernen.apps.erinnerung;

class SpeicherImArbeitsspeicherTest extends ErinnerungsspeicherVertragTest {

    @Override
    Erinnerungsspeicher speicher() {
        return new SpeicherImArbeitsspeicher();
    }
}
