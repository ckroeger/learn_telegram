package telegram.lernen.miniapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Prüft die {@code initData}, die eine Mini App an den Server schickt. Nur wer den Token des Bots kennt, kann den
 * Hash richtig berechnen. Die Mini App selbst kennt ihn nie.
 *
 * <p>Ohne diese Prüfung kann jeder dem Server beliebige Nutzerdaten schicken: {@code initDataUnsafe} im Browser
 * ist nur eine Bequemlichkeit für die Anzeige, keine Sicherheit.
 */
public final class InitDataPruefer {

    /** Wie alt {@code auth_date} höchstens sein darf, damit abgefangene Daten nicht ewig gelten. */
    public static final Duration STANDARD_MAX_ALTER = Duration.ofHours(1);
    /** Echte initData sind klein. Alles darüber wird gar nicht erst zerlegt. */
    static final int MAX_ZEICHEN = 16 * 1024;
    /** Uhren gehen ein wenig verschieden. */
    private static final Duration ZUKUNFT_ERLAUBT = Duration.ofMinutes(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] geheimerSchluessel;
    private final Duration maxAlter;

    public InitDataPruefer(String botToken, Duration maxAlter) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("Ohne Token kann nichts geprüft werden");
        }
        // Der Schlüssel ist der HMAC des Tokens, mit der festen Zeichenkette "WebAppData" als Schlüssel.
        this.geheimerSchluessel = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        this.maxAlter = maxAlter;
    }

    // #region pruefen
    /** Gibt die Daten zurück, wenn sie von Telegram stammen und frisch sind, sonst fliegt {@link UngueltigeInitData}. */
    public GepruefteInitData pruefen(String initData, Instant jetzt) throws UngueltigeInitData {
        Map<String, String> felder = zerlegen(initData);
        String hash = felder.remove("hash");
        if (hash == null) {
            throw new UngueltigeInitData("Das Feld hash fehlt");
        }
        // Alle Felder außer hash, nach Namen sortiert, als "name=wert" und mit Zeilenumbruch getrennt.
        String pruefstring = new TreeMap<>(felder).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] erwartet = hmac(geheimerSchluessel, pruefstring.getBytes(StandardCharsets.UTF_8));
        // Zeitkonstanter Vergleich: nicht abbrechen beim ersten falschen Byte.
        if (!MessageDigest.isEqual(erwartet, hexZuBytes(hash))) {
            throw new UngueltigeInitData("Die Signatur stimmt nicht");
        }

        Instant authDate = authDate(felder.get("auth_date"));
        if (authDate.plus(maxAlter).isBefore(jetzt)) {
            throw new UngueltigeInitData("Die Daten sind zu alt, bitte die Mini App neu öffnen");
        }
        if (authDate.isAfter(jetzt.plus(ZUKUNFT_ERLAUBT))) {
            throw new UngueltigeInitData("auth_date liegt in der Zukunft");
        }
        return new GepruefteInitData(Map.copyOf(felder), authDate, felder.get("query_id"), user(felder.get("user")));
    }
    // #endregion pruefen

    /** Zerlegt {@code a=1&b=2} und dekodiert Namen und Werte. Doppelte Namen sind verdächtig und werden abgelehnt. */
    static Map<String, String> zerlegen(String initData) throws UngueltigeInitData {
        if (initData == null || initData.isEmpty()) {
            throw new UngueltigeInitData("initData fehlen");
        }
        if (initData.length() > MAX_ZEICHEN) {
            throw new UngueltigeInitData("initData sind zu lang");
        }
        Map<String, String> felder = new HashMap<>();
        for (String paar : initData.split("&")) {
            int gleich = paar.indexOf('=');
            if (gleich <= 0) {
                throw new UngueltigeInitData("initData sind kein gültiger Query-String");
            }
            try {
                String name = URLDecoder.decode(paar.substring(0, gleich), StandardCharsets.UTF_8);
                String wert = URLDecoder.decode(paar.substring(gleich + 1), StandardCharsets.UTF_8);
                if (felder.put(name, wert) != null) {
                    throw new UngueltigeInitData("Das Feld " + name + " kommt doppelt vor");
                }
            } catch (IllegalArgumentException e) {
                throw new UngueltigeInitData("initData enthalten ungültige Zeichen");
            }
        }
        return felder;
    }

    private static byte[] hexZuBytes(String hex) throws UngueltigeInitData {
        if (hex.length() != 64) {
            throw new UngueltigeInitData("Die Signatur stimmt nicht");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new UngueltigeInitData("Die Signatur stimmt nicht");
        }
    }

    private static Instant authDate(String wert) throws UngueltigeInitData {
        if (wert == null) {
            throw new UngueltigeInitData("Das Feld auth_date fehlt");
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(wert));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new UngueltigeInitData("auth_date ist keine Zeit");
        }
    }

    private static JsonNode user(String wert) throws UngueltigeInitData {
        if (wert == null) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode user = JSON.readTree(wert);
            if (user == null || !user.isObject()) {
                throw new UngueltigeInitData("Das Feld user ist kein Objekt");
            }
            return user;
        } catch (JsonProcessingException e) {
            throw new UngueltigeInitData("Das Feld user ist kein gültiges JSON");
        }
    }

    private static byte[] hmac(byte[] schluessel, byte[] daten) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(schluessel, "HmacSHA256"));
            return mac.doFinal(daten);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 fehlt in dieser Java-Laufzeit", e);
        }
    }
}
