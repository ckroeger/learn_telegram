package telegram.lernen.miniapp;

/** Die {@code initData} einer Mini App sind nicht echt, zu alt oder kaputt. Die Meldung enthält nie den Token. */
public class UngueltigeInitData extends Exception {

    public UngueltigeInitData(String grund) {
        super(grund);
    }
}
