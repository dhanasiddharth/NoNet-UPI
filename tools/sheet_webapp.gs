/**
 * Finance app — portfolio listing endpoint (Google Apps Script).
 *
 * The app's "Sync listing" button calls this to pull the raw trade tabs from
 * the portfolio sheet. Simplest workable auth for a single-user, no-backend
 * setup: the web app runs as the sheet owner and access is gated by a long
 * random token. The sheet itself stays private.
 *
 * SETUP (one time, ~2 minutes):
 *  1. Open the portfolio sheet → Extensions → Apps Script.
 *  2. Paste this file. Replace TOKEN below with a long random string
 *     (e.g. `openssl rand -hex 24`).
 *  3. Deploy → New deployment → Web app:
 *       Execute as: Me
 *       Who has access: Anyone with the link
 *  4. Copy the deployment URL. In the app: Money tab → Sync → enter URL + token
 *     (stored encrypted on-device; asked only once).
 *
 * To rotate access: change TOKEN and redeploy.
 */

const TOKEN = 'REPLACE_WITH_LONG_RANDOM_TOKEN';

// Tabs the app normalizes on-device (owner/broker come from the tab name)
const TABS = [
  'Sid Kite EQ', 'Sid Kite MF', 'Vino Kite EQ', 'Vino Kite MF', 'Ilan Kite MF',
  'Grow', 'Sid IBKR', 'Vino IBKR', 'ISIN Ticker', 'Gold', 'Crypto',
];

function doGet(e) {
  if (!e || !e.parameter || e.parameter.token !== TOKEN) {
    return ContentService.createTextOutput(JSON.stringify({ error: 'unauthorized' }))
      .setMimeType(ContentService.MimeType.JSON);
  }
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const out = { fetchedAt: new Date().toISOString(), tabs: {} };
  for (const name of TABS) {
    const sh = ss.getSheetByName(name);
    if (!sh) continue;
    // Display values keep dates as the strings the app's parser expects
    out.tabs[name] = sh.getDataRange().getDisplayValues();
  }
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}
