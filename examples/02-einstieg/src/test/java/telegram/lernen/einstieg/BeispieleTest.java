package telegram.lernen.einstieg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import telegram.lernen.testsupport.FakeTelegramServer;

class BeispieleTest {

    private static final String TOKEN = "123456:GEHEIM";

    @Test
    void werBinIchZeigtDasProfil() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            assertEquals("Bot Fake Bot (@fake_bot), id 1", WerBinIch.profil(new BotApi(TOKEN, fake.baseUrl())));
        }
    }

    @Test
    void botProfilSetztDieKurzbeschreibungUndLiestSieZurueck() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("setMyShortDescription", "true");
            fake.onMethod("getMyShortDescription", "{\"short_description\":\"Mein Lern-Bot\"}");

            String zurueck = BotProfil.kurzbeschreibungSetzen(new BotApi(TOKEN, fake.baseUrl()), "Mein Lern-Bot");

            assertEquals("Mein Lern-Bot", zurueck);
            assertTrue(fake.requests("setMyShortDescription").get(0).bodyContains("\"short_description\":\"Mein Lern-Bot\""));
            assertEquals(1, fake.requests("getMyShortDescription").size());
        }
    }

    @Test
    void uebungLoesungSetztDieBeschreibung() throws Exception {
        try (FakeTelegramServer fake = FakeTelegramServer.start()) {
            fake.onMethod("setMyDescription", "true");
            fake.onMethod("getMyDescription", "{\"description\":\"Lang und breit\"}");

            String zurueck = UebungLoesung.beschreibungSetzen(new BotApi(TOKEN, fake.baseUrl()), "Lang und breit");

            assertEquals("Lang und breit", zurueck);
            assertTrue(fake.requests("setMyDescription").get(0).bodyContains("\"description\":\"Lang und breit\""));
        }
    }
}
