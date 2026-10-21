// README 그림 찍기: node docs/readme/capture.js (Playwright 필요). section.cap 마다 docs/img/readme-<id>.png 를 2배율로 쓴다.
const path = require('path');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  const page = await browser.newPage({ viewport: { width: 940, height: 900 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(__dirname, 'index.html'), { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  for (const el of await page.$$('section.cap')) {
    const id = await el.getAttribute('id');
    await el.screenshot({ path: path.join(__dirname, '..', 'img', 'readme-' + id + '.png') });
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
