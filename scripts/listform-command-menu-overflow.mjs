// Run from the repository root. Uses installed Vaadin components and the production overflow code.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { build } from 'esbuild';
const require = createRequire(import.meta.url);
const playwrightPath = process.env.PLAYWRIGHT_PACKAGE || 'C:/Users/strim/AppData/Local/Programs/Python/Python312/Lib/site-packages/playwright/driver/package';
const { chromium } = require(playwrightPath);
const output = process.argv[2] || 'outputs/verification/listform-command-menu-fixes-20261108';
mkdirSync(output, { recursive: true });
const source = readFileSync('platform-vaadin/src/main/java/org/ipro/form/CommandBar.java', 'utf8');
const block = source.split('private static final String OVERFLOW_JS =')[1].split('private void installOverflowMeasurement()')[0];
const js = [...block.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => JSON.parse(m[0])).join('');
const entry = `
import '@vaadin/button/vaadin-button.js';
import '@vaadin/menu-bar/vaadin-menu-bar.js';
const menu = document.querySelector('[data-cb-more]');
menu.items = [{text:'Ещё',children:[{text:'Создать'},{text:'Изменить'},
{text:'Обновить',disabled:true},{text:'Настройки',children:[{text:'Группировка'}]},
{text:'Сервис',children:[{text:'Скопировать ссылку'}]}]}];
window.probeReady = true;`;
const bundle = await build({stdin:{contents:entry,resolveDir:process.cwd()},bundle:true,format:'iife',write:false,logLevel:'error'});
const browser = await chromium.launch({headless:true,
  ...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} :
  {executablePath:'C:/Users/strim/AppData/Local/ms-playwright/chromium_headless_shell-1208/chrome-headless-shell-win64/chrome-headless-shell.exe'})});
try {
  const page = await browser.newPage({viewport:{width:820,height:460}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setContent(`<!doctype html><html lang="ru"><meta charset="utf-8"><style>
body{font:16px Arial;padding:24px;background:#f6f8fa;color:#1b2733}
#bar{display:flex;gap:8px;width:700px;background:white;padding:12px;border:1px solid #ccd3db;border-radius:8px}
[data-cb-row]{display:flex;gap:8px;flex:1 1 auto;min-width:0;overflow:hidden}
[data-cb-command]{width:140px;flex-shrink:0}
[data-cb-more]{flex-shrink:0}
</style><h2>Панель команд списка</h2><div id="bar"><div data-cb-row>
<vaadin-button data-cb-command aria-label="Создать">Создать</vaadin-button>
<vaadin-button data-cb-command aria-label="Изменить">Изменить</vaadin-button>
<vaadin-button data-cb-command aria-label="Обновить">Обновить</vaadin-button>
</div><vaadin-menu-bar data-cb-more></vaadin-menu-bar></div></html>`);
  await page.addScriptTag({content:bundle.outputFiles[0].text});
  await page.waitForFunction(() => window.probeReady && document.querySelector('vaadin-menu-bar-button'));
  await page.evaluate(code => new Function(code).call(document.querySelector('#bar')), js);
  await page.waitForFunction(() => document.querySelector('#bar').__cbInstalled);
  await page.screenshot({path:`${output}/wide.png`});
  await page.locator('[data-cb-command]').last().focus();
  await page.evaluate(() => document.querySelector('#bar').style.width = '250px');
  await page.waitForFunction(() => document.querySelectorAll('[data-cb-command]')[2].style.display === 'none');
  const focus = await page.evaluate(() => ({
    tag:document.activeElement.localName,
    text:document.activeElement.textContent.trim(),
    onMore:!!document.activeElement.closest('[data-cb-more]'),
    menuOpen:!!document.querySelector('[data-cb-more] [aria-expanded="true"]')
  }));
  assert.equal(focus.onMore, true);
  assert.equal(focus.menuOpen, false);
  await page.screenshot({path:`${output}/narrow-focused.png`});
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => !!document.querySelector('[data-cb-more] [aria-expanded="true"]'));
  await page.screenshot({path:`${output}/narrow-menu.png`});
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('[data-cb-more] [aria-expanded="true"]'));
  assert.equal(await page.evaluate(() => !!document.activeElement.closest('[data-cb-more]')), true);
  const widths = [];
  for (const width of [240, 320, 480, 700]) {
    await page.evaluate(w => { document.querySelector('#bar').style.width = `${w}px`; document.querySelector('#bar').__cbMeasure(); }, width);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const state = await page.evaluate(() => {
      const buttons = [...document.querySelectorAll('[data-cb-command]')];
      const visible = buttons.filter(el => getComputedStyle(el).display !== 'none');
      return {visible:visible.map(el => el.textContent),
        tops:visible.map(el => el.getBoundingClientRect().top),
        sequence:buttons.map(el => getComputedStyle(el).display !== 'none'),
        menu:document.querySelector('[data-cb-more]').items[0].children.map(item => item.text)};
    });
    assert.ok(state.tops.every(top => top === state.tops[0]), 'commands wrapped');
    assert.ok(!state.sequence.some((shown, i) => shown && state.sequence.slice(0,i).includes(false)), 'prefix broken');
    assert.deepEqual(state.menu, ['Создать','Изменить','Обновить','Настройки','Сервис']);
    widths.push({width,...state});
  }
  assert.equal(widths.at(-1).visible.length, 3, 'buttons did not return');
  assert.deepEqual(errors, []);
  const result = {browser:browser.version(),focus,widths,keyboard:'Enter / ArrowDown / Escape passed',errors};
  writeFileSync(`${output}/overflow-results.json`,JSON.stringify(result,null,2));
  console.log(JSON.stringify(result,null,2));
} finally { await browser.close(); }
