package telegram.lernen.miniapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class InitDataPrueferTest {

    static final String TOKEN = "123456789:AAH-nur-fuer-tests";

    /**
     * Testvektor 1, mit einem eigenen Python-Skript (hmac, hashlib) berechnet und nicht mit dieser Klasse. Er enthält
     * query_id, user als JSON und ein Feld signature, das in die Prüfung einfließt (nur hash bleibt draußen).
     */
    static final String VEKTOR_1 = "auth_date=1700000000&query_id=AAHdF6IQAAAAAN0XohDhrOrc&user=%7B%22id%22%3A279058397%2C"
            + "%22first_name%22%3A%22Anna%22%2C%22last_name%22%3A%22Beispiel%22%2C%22username%22%3A%22anna_b%22%2C"
            + "%22language_code%22%3A%22de%22%7D&signature=c2lnbmF0dXJlLW5pY2h0LXByw7xmYmFy&hash="
            + "be20884ce6381e516d25bf9d2fb27ba64faca7647ffab33af843a49ea0235fad";
    /** Testvektor 2: Umlaute, "&" und Leerzeichen im Namen, keine query_id. */
    static final String VEKTOR_2 = "auth_date=1700000500&user=%7B%22id%22%3A42%2C%22first_name%22%3A%22J%C3%BCrgen%20%26%20S%C3%B6hne"
            + "%22%2C%22username%22%3A%22j%22%7D&hash=3343d1e7c523d3b435f90ad532540e9b846e3193cd34c80590785332d4e62e44";

    private static final Instant JETZT = Instant.ofEpochSecond(1_700_000_600);
    private final InitDataPruefer pruefer = new InitDataPruefer(TOKEN, Duration.ofHours(1));

    /** Baut gültige initData mit demselben Verfahren wie Telegram, damit die Tests Felder verändern können. */
    static String signiert(String token, Map<String, String> felder) throws Exception {
        String pruefstring = new TreeMap<>(felder).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] schluessel = mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        mac.init(new SecretKeySpec(schluessel, "HmacSHA256"));
        String hash = java.util.HexFormat.of().formatHex(mac.doFinal(pruefstring.getBytes(StandardCharsets.UTF_8)));
        StringBuilder query = new StringBuilder();
        felder.forEach((name, wert) -> query.append(name).append('=')
                .append(URLEncoder.encode(wert, StandardCharsets.UTF_8)).append('&'));
        return query + "hash=" + hash;
    }

    private static void abgelehnt(InitDataPruefer pruefer, String initData, String teilDerMeldung) {
        UngueltigeInitData e = assertThrows(UngueltigeInitData.class, () -> pruefer.pruefen(initData, JETZT));
        assertTrue(e.getMessage().contains(teilDerMeldung), e.getMessage());
    }

    @Test
    void testvektor1WirdAngenommenUndAusgelesen() throws Exception {
        GepruefteInitData daten = pruefer.pruefen(VEKTOR_1, JETZT);

        assertEquals(279058397L, daten.userId());
        assertEquals("Anna", daten.vorname());
        assertEquals("anna_b", daten.username());
        assertEquals("AAHdF6IQAAAAAN0XohDhrOrc", daten.queryId());
        assertTrue(daten.kannAntworten());
        assertEquals(Instant.ofEpochSecond(1_700_000_000), daten.authDate());
        assertFalse(daten.felder().containsKey("hash"));
    }

    @Test
    void testvektor2MitUmlautenUndAmpersandWirdAngenommen() throws Exception {
        GepruefteInitData daten = pruefer.pruefen(VEKTOR_2, JETZT);

        assertEquals("Jürgen & Söhne", daten.vorname());
        assertNull(daten.queryId());
        assertFalse(daten.kannAntworten());
    }

    @Test
    void eigenerHelferErzeugtDieselbenHashesWieDiePythonVektoren() throws Exception {
        String user = "{\"id\":42,\"first_name\":\"Jürgen & Söhne\",\"username\":\"j\"}";
        String selbst = signiert(TOKEN, Map.of("auth_date", "1700000500", "user", user));

        assertEquals(hashVon(VEKTOR_2), hashVon(selbst));
    }

    private static String hashVon(String initData) {
        return initData.substring(initData.indexOf("hash=") + 5);
    }

    @Test
    void eineVeraenderteAngabeBrichtDieSignatur() {
        abgelehnt(pruefer, VEKTOR_1.replace("anna_b", "mallory"), "Signatur");
        abgelehnt(pruefer, VEKTOR_1.replace("auth_date=1700000000", "auth_date=1700000001"), "Signatur");
        abgelehnt(pruefer, VEKTOR_1.replace("signature=c2lnbmF0", "signature=c2lnbmF1"), "Signatur");
    }

    @Test
    void einFremderTokenBrichtDieSignatur() {
        abgelehnt(new InitDataPruefer("987654321:ANDERER", Duration.ofHours(1)), VEKTOR_1, "Signatur");
    }

    @Test
    void einZusaetzlichesFeldBrichtDieSignatur() {
        abgelehnt(pruefer, "extra=1&" + VEKTOR_1, "Signatur");
    }

    @Test
    void fehlenderOderKaputterHashWirdAbgelehnt() {
        abgelehnt(pruefer, VEKTOR_1.substring(0, VEKTOR_1.indexOf("&hash=")), "hash fehlt");
        abgelehnt(pruefer, VEKTOR_1.replaceAll("hash=.*$", "hash=abcd"), "Signatur");
        abgelehnt(pruefer, VEKTOR_1.replaceAll("hash=.*$", "hash=" + "zz".repeat(32)), "Signatur");
        abgelehnt(pruefer, VEKTOR_1.replaceAll("hash=.*$", "hash="), "Signatur");
    }

    @Test
    void grossgeschriebenerHashIstGleichwertigeHexschreibweise() throws Exception {
        String gross = VEKTOR_1.replaceAll("hash=.*$", "hash=") + hashVon(VEKTOR_1).toUpperCase();

        assertEquals(279058397L, pruefer.pruefen(gross, JETZT).userId());
    }

    @Test
    void zuAlteDatenWerdenAbgelehntAberDieGrenzeGilt() throws Exception {
        Instant genau = Instant.ofEpochSecond(1_700_000_000).plus(Duration.ofHours(1));

        assertEquals(279058397L, pruefer.pruefen(VEKTOR_1, genau).userId());
        UngueltigeInitData e = assertThrows(UngueltigeInitData.class, () -> pruefer.pruefen(VEKTOR_1, genau.plusSeconds(1)));
        assertTrue(e.getMessage().contains("zu alt"));
    }

    @Test
    void datenAusDerZukunftWerdenAbgelehntBisAufEineMinuteUhrenabweichung() throws Exception {
        Instant vorher = Instant.ofEpochSecond(1_700_000_000).minusSeconds(60);

        assertEquals(279058397L, pruefer.pruefen(VEKTOR_1, vorher).userId());
        UngueltigeInitData e = assertThrows(UngueltigeInitData.class, () -> pruefer.pruefen(VEKTOR_1, vorher.minusSeconds(1)));
        assertTrue(e.getMessage().contains("Zukunft"));
    }

    @Test
    void authDateMussVorhandenUndEineZahlSein() throws Exception {
        abgelehnt(pruefer, signiert(TOKEN, Map.of("user", "{\"id\":1}")), "auth_date fehlt");
        abgelehnt(pruefer, signiert(TOKEN, Map.of("auth_date", "gestern")), "keine Zeit");
        abgelehnt(pruefer, signiert(TOKEN, Map.of("auth_date", "99999999999999999999")), "keine Zeit");
    }

    @Test
    void userMussEinJsonObjektSein() throws Exception {
        String frisch = "1700000500";
        abgelehnt(pruefer, signiert(TOKEN, Map.of("auth_date", frisch, "user", "kein json")), "kein gültiges JSON");
        abgelehnt(pruefer, signiert(TOKEN, Map.of("auth_date", frisch, "user", "[1]")), "kein Objekt");
        GepruefteInitData ohneUser = pruefer.pruefen(signiert(TOKEN, Map.of("auth_date", frisch)), JETZT);
        assertEquals(0L, ohneUser.userId());
        assertNull(ohneUser.username());
    }

    @Test
    void kaputteQueryStringsWerdenAbgelehnt() {
        abgelehnt(pruefer, "", "fehlen");
        abgelehnt(pruefer, "ohne-gleichheitszeichen", "Query-String");
        abgelehnt(pruefer, "=wert&hash=00", "Query-String");
        abgelehnt(pruefer, "a=%ZZ&hash=00", "ungültige Zeichen");
        abgelehnt(pruefer, "a=1&a=2&hash=00", "doppelt");
        abgelehnt(pruefer, "a=" + "x".repeat(InitDataPruefer.MAX_ZEICHEN), "zu lang");
    }

    @Test
    void nullAlsInitDataIstEinFehlerKeineNullPointerException() {
        abgelehnt(pruefer, null, "fehlen");
    }

    @Test
    void leererTokenIstNichtErlaubt() {
        assertThrows(IllegalArgumentException.class, () -> new InitDataPruefer("", Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new InitDataPruefer(null, Duration.ofHours(1)));
    }

    @Test
    void meldungenEnthaltenWederTokenNochHash() {
        UngueltigeInitData e = assertThrows(UngueltigeInitData.class, () -> pruefer.pruefen(VEKTOR_1.replace("anna_b", "x"), JETZT));

        assertFalse(e.getMessage().contains(TOKEN) || e.getMessage().contains(hashVon(VEKTOR_1)));
    }
}
