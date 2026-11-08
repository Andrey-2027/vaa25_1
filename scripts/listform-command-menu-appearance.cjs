// Visual regressions for the actual list command bar. Run against the owned CM5 pilot.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'C:/Users/strim/AppData/Local/Programs/Python/Python312/Lib/site-packages/playwright/driver/package');
const run = path.resolve(process.argv[2] || 'outputs/verification/listform-command-menu-cm5-20261108');
const out = path.resolve(process.argv[3] || 'outputs/verification/listform-command-menu-ui-20261108');
const state = JSON.parse(fs.readFileSync(path.join(run,'state.json'),'utf8').replace(/^\uFEFF/,''));
if (!/^accounting_object_pilot_cm5_[a-f0-9]{16}$/.test(state.database) || !/^http:\/\/127\.0\.0\.1:\d+$/.test(state.url)) throw Error('Expected owned isolated pilot');
fs.mkdirSync(out,{recursive:true});
let browser,page;
const receipt = {cases:[],pageErrors:[]};
const more = () => page.getByRole('menuitem',{name:'Дополнительные команды',exact:true}).filter({visible:true}).first();
async function evidence(name) {
 fs.writeFileSync(path.join(out,name+'.aria.txt'),await page.locator('body').ariaSnapshot());
 await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});
}
async function menus() {
 return page.getByRole('menu').evaluateAll(els=>els.map(el=>{
  const items = el.shadowRoot?.querySelector('[part="items"]');
  const r = el.getBoundingClientRect();
  return {tag:el.localName,left:r.left,right:r.right,width:r.width,client:items?.clientWidth??el.clientWidth,
   scroll:items?.scrollWidth??el.scrollWidth,overflow:items?getComputedStyle(items).overflowX:getComputedStyle(el).overflowX};
 }));
}
function checkMenus(values,width) {
 assert.ok(values.length>0,'menu missing');
 for(const value of values) {
  assert.ok(value.scroll<=value.client+1,'horizontal overflow: '+JSON.stringify(value));
  assert.ok(value.left>=-1 && value.right<=width+1,'menu outside viewport: '+JSON.stringify(value));
 }
}
(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH || 'C:/Users/strim/AppData/Local/ms-playwright/chromium_headless_shell-1208/chrome-headless-shell-win64/chrome-headless-shell.exe'});
 receipt.browser=browser.version();
 const context=await browser.newContext({viewport:{width:1100,height:850},locale:'ru-RU'});
 page=await context.newPage();page.on('pageerror',e=>receipt.pageErrors.push(e.message));
 await page.goto(state.url+'/login');
 await page.getByRole('textbox',{name:'Username',exact:true}).fill('accounting-pilot');
 await page.getByRole('textbox',{name:'Password',exact:true}).fill(fs.readFileSync(path.join(run,'login.local'),'utf8').trim());
 await page.getByRole('button',{name:'Login',exact:true}).click();
 await page.getByRole('navigation').waitFor();
 const toast=page.getByRole('button',{name:'Close',exact:true});if(await toast.count())await toast.first().click();
 await page.getByRole('navigation').getByRole('listitem').filter({hasText:'Справочники'}).first().click();
 await page.getByRole('button',{name:'Цеха',exact:true}).click();
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).waitFor();
 await page.locator('[data-cb-view="icon"]').first().waitFor();
 const styles=await page.locator('[data-cb-command]').filter({visible:true}).evaluateAll(els=>els.map(el=>{
  const s=getComputedStyle(el),r=el.getBoundingClientRect();
  return {title:el.getAttribute('aria-label'),view:el.getAttribute('data-cb-view'),primary:el.getAttribute('theme')?.includes('primary')||false,
   top:r.top,height:r.height,background:s.backgroundImage,border:s.borderTopWidth,borderStyle:s.borderTopStyle};
 }));
 assert.ok(styles.every(s=>s.height===styles[0].height&&s.top===styles[0].top),'button sizes differ');
 receipt.styles=styles;
 receipt.dom=await page.locator('[data-cb-row]').filter({visible:true}).first().evaluate(el=>({parent:el.parentElement.outerHTML.slice(0,240),links:[...document.querySelectorAll('link[rel=stylesheet]')].map(el=>el.href)}));
 const resource=await page.request.get(state.url+'/VAADIN/static/ipro/command-bar.css');receipt.resource={status:resource.status(),type:resource.headers()['content-type']};
 const ordinary=styles.filter(s=>!s.primary);
 assert.ok(ordinary.every(s=>s.background===ordinary[0].background&&s.border==='1px'&&s.borderStyle==='solid'),'button styles differ');
 receipt.styles=styles;
 await evidence('panel-unified');
 await page.getByRole('button',{name:'Обновить',exact:true}).hover();
 await page.getByRole('tooltip').filter({hasText:'Обновить'}).waitFor();
 await more().click();
 const copy=page.getByRole('menuitem',{name:'Копировать',exact:true});await copy.waitFor();
 assert.equal((await copy.innerText()).trim(),'Копировать','reason leaked into caption');
 assert.equal(await copy.getAttribute('aria-disabled'),'true');
 receipt.root=await menus();checkMenus(receipt.root,1100);
 await copy.hover();
 await page.getByRole('tooltip').filter({hasText:'Сначала выберите строку'}).waitFor();
 await evidence('disabled-tooltip');
 await copy.click({force:true});
 assert.equal(await page.getByRole('dialog').count(),0,'disabled command executed');
 receipt.cases.push('Unified button height/background/border; disabled caption stays clean; hover tooltip works; disabled click does not execute');
 await page.getByRole('menuitem',{name:/^Сервис/}).click();
 await page.getByRole('menuitem',{name:/^Разработка/}).waitFor();
 receipt.service=await menus();checkMenus(receipt.service,1100);
 await evidence('service-without-scroll');
 await page.getByRole('menuitem',{name:/^Разработка/}).hover();
 await page.getByRole('menuitem',{name:'Открыть в Entity Explorer',exact:true}).waitFor();
 receipt.development=await menus();checkMenus(receipt.development,1100);
 await evidence('development-without-scroll');
 await page.keyboard.press('Escape');await page.keyboard.press('Escape');await page.keyboard.press('Escape');
 await page.setViewportSize({width:360,height:850});
 await more().click();
 await page.getByRole('menuitem',{name:'Копировать',exact:true}).waitFor();
 receipt.narrowRoot=await menus();checkMenus(receipt.narrowRoot,360);
 await page.getByRole('menuitem',{name:/^Сервис/}).click();
 await page.getByRole('menuitem',{name:/^Разработка/}).waitFor();
 receipt.narrowService=await menus();checkMenus(receipt.narrowService,360);
 await evidence('narrow-service-without-scroll');
 receipt.cases.push('More, Service and Development have no horizontal overflow; submenus remain inside 1100px and 360px viewports');
 assert.deepEqual(receipt.pageErrors,[]);receipt.status='passed';console.log(JSON.stringify(receipt,null,2));
})().catch(async e=>{receipt.status='failed';receipt.error=String(e);console.error(e);if(page)await evidence('failure').catch(()=>{});process.exitCode=1;})
 .finally(async()=>{fs.writeFileSync(path.join(out,'appearance-results.json'),JSON.stringify(receipt,null,2));await browser?.close();});
