const path = require('node:path');
const fs = require('node:fs');
const puppeteer = require(require.resolve('puppeteer', {paths: ['/home/chen/.nvm/versions/node/v24.13.1/lib/node_modules/@mermaid-js/mermaid-cli']}));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: '/home/chen/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({width: 1600, height: 1040, deviceScaleFactor: 1.5});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('file://' + path.join("/home/chen/leisure/flow/docs/figures/breeze-top-interface", 'render.html'), {waitUntil: 'networkidle2', timeout: 45000});
    await page.waitForFunction(() => document.querySelectorAll('.mxgraph svg g').length > 15, {timeout: 30000});
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({path: path.join("/home/chen/leisure/flow/docs/figures/breeze-top-interface", 'breeze-top-interface.png'), fullPage: true});
    const report = await page.evaluate(() => ({
      svgCount: document.querySelectorAll('.mxgraph svg').length,
      text: document.querySelector('.mxgraph').innerText,
      width: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight,
    }));
    report.pageErrors = errors;
    fs.writeFileSync(path.join("/home/chen/leisure/flow/docs/figures/breeze-top-interface", 'render-check.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error);process.exitCode = 1;});
