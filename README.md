# learn_telegram

Begleit-Repo der Lernseite zur Telegram-API (Bot API, Mini Apps, Login Widget) mit Java-Beispielen.

**Die Seite:** https://ckroeger.github.io/learn_telegram/

Dieses Repo wird automatisch aus dem Quell-Repo erzeugt: `docs/` ist die gebaute Seite, `examples/` und `apps/` sind die Java-Beispiele der Lektionen. Änderungen bitte nicht hier, sondern als Hinweis (Issue) an dieses Repo.

## Beispiele ausführen

Voraussetzung ist JDK 21. Maven ist nicht nötig, der Wrapper lädt es selbst.

```bash
git clone https://github.com/ckroeger/learn_telegram.git
cd learn_telegram
./mvnw verify
```

Den Token für die Beispiele legst du, wie auf der Seite im Kapitel „Vorbereitung" beschrieben, in `TELEGRAM_BOT_TOKEN` ab (Vorlage: `.env.example`). Der Token gehört nie in den Code oder in ein Repo.

## Lizenz

- Texte (`docs/`): CC BY 4.0, siehe `LICENSE-CONTENT`
- Code (`examples/`, `apps/`): MIT, siehe `LICENSE`
