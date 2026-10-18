package telegram.lernen;

public class UebungLoesung {

    // #region loesung
    public static String grussFuer(String name) {
        return Hallo.gruss() + ", " + name + "!";
    }
    // #endregion loesung

    public static void main(String[] args) {
        System.out.println(grussFuer("Welt"));
    }
}
