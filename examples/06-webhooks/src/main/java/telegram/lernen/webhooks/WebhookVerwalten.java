package telegram.lernen.webhooks;

import telegram.lernen.client.TelegramClient;

/** Zeigt den Webhook-Status ({@code info}) oder entfernt ihn ({@code loeschen}), z. B. um zu getUpdates zurückzukehren. */
public class WebhookVerwalten {

    public static void main(String[] args) throws Exception {
        TelegramClient client = TelegramClient.ausUmgebung();
        String befehl = args.length > 0 ? args[0] : "info";
        switch (befehl) {
            case "info" -> System.out.println(Webhook.info(client).toPrettyString());
            case "loeschen" -> {
                Webhook.loeschen(client, false);
                System.out.println("Webhook entfernt. getUpdates funktioniert wieder.");
            }
            default -> {
                System.err.println("Aufruf: WebhookVerwalten info | loeschen");
                System.exit(2);
            }
        }
    }
}
