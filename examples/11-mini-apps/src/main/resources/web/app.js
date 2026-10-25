'use strict';

const tg = window.Telegram && window.Telegram.WebApp;
const status = document.getElementById('status');

async function postJson(pfad, daten) {
  const antwort = await fetch(pfad, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(daten),
  });
  try {
    return await antwort.json();
  } catch (e) {
    return { ok: false, fehler: 'Unerwartete Antwort (HTTP ' + antwort.status + ')' };
  }
}

// Alles vom Server oder von Telegram kommt per textContent in die Seite, nie als HTML.
function setzeText(id, text) {
  document.getElementById(id).textContent = text == null ? '-' : String(text);
}

async function geprueft() {
  // Der Server prüft den Hash. Die Daten im Browser (initDataUnsafe) sind nur Anzeige.
  const ergebnis = await postJson('/verify', { initData: tg.initData });
  if (!ergebnis.ok) {
    status.textContent = 'Die Prüfung ist fehlgeschlagen: ' + ergebnis.fehler;
    return;
  }
  status.textContent = 'Der Server hat bestätigt, dass die Daten von Telegram kommen.';
  setzeText('vorname', ergebnis.vorname);
  setzeText('username', ergebnis.username);
  setzeText('nutzerid', ergebnis.id);
  document.getElementById('geprueft').hidden = false;

  const knopf = document.getElementById('senden');
  if (!ergebnis.kannAntworten) {
    knopf.disabled = true;
    status.textContent += ' Zum Senden bitte über die Menütaste oder die Inline-Taste öffnen.';
    return;
  }
  knopf.addEventListener('click', async () => {
    knopf.disabled = true;
    const text = document.getElementById('text').value;
    const antwort = await postJson('/antworten', { initData: tg.initData, text });
    status.textContent = antwort.ok ? 'Gesendet.' : 'Senden fehlgeschlagen: ' + antwort.fehler;
    knopf.disabled = false;
  });
}

function ueberTastatur() {
  // Über eine Antwort-Taste geöffnet gibt es keine initData: der Weg zurück ist sendData.
  status.textContent = 'Über eine Antwort-Taste geöffnet: keine initData. Der Text geht mit sendData an den Bot.';
  document.getElementById('formular').hidden = false;
  document.getElementById('datensenden').addEventListener('click', () => {
    tg.sendData(JSON.stringify({ text: document.getElementById('formtext').value }));
  });
}

if (!tg || tg.platform === 'unknown') {
  status.textContent = 'Diese Seite ist eine Mini App und läuft nur in Telegram.';
} else {
  tg.ready();
  tg.expand();
  if (tg.initData) {
    geprueft();
  } else {
    ueberTastatur();
  }
}
