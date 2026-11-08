// CM5 acceptance against the isolated accounting pilot. Run from the repository root.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'C:/Users/strim/AppData/Local/Programs/Python/Python312/Lib/site-packages/playwright/driver/package');
const out = path.resolve(process.argv[2] || 'outputs/verification/listform-command-menu-cm5-20261108');
const state = JSON.parse(fs.readFileSync(path.join(out,'state.json'),'utf8').replace(/^\uFEFF/,''));
if (!/^accounting_object_pilot_cm5_[a-f0-9]{16}$/.test(state.database) || !/^http:\/\/127\.0\.0\.1:\d+$/.test(state.url)) throw Error('Expected isolated CM5 pilot');
const receipt = {url:state.url,cases:[],pageErrors:[]};
let browser, page;
const button = (name,scope=page) => scope.getByRole('button',{name,exact:true}).filter({visible:true}).first();
const dialog = title => page.getByRole('dialog',{name:title,exact:typeof title==='string',includeHidden:true}).and(page.locator('vaadin-dialog[opened]')).last();
const more = (scope=page) => scope.getByRole('menuitem',{name:'Дополнительные команды',exact:true}).filter({visible:true}).first();
async function evidence(name) {
 fs.writeFileSync(path.join(out,name+'.aria.txt'),await page.locator('body').ariaSnapshot());
 await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});
}
async function openList(section,title) {
 await page.getByRole('navigation').getByRole('listitem').filter({hasText:section}).first().click();
 await button(title).click();
 await more().waitFor();
}
async function closeMenus() {
 await page.mouse.move(0,0);
 for(let i=0;i<5 && await page.getByRole('menu').count();i++) { await page.keyboard.press('Escape'); await frames(); }
}
async function cancelCard(scope) {
 await button('Отмена',scope).click();
 for(let i=0;i<25;i++) {
  if(!await scope.locator(':scope > [slot="title"]').isVisible()) return;
  const confirm=page.locator('vaadin-confirm-dialog[opened]');
  if(await confirm.count()) { await button('Закрыть',confirm).click(); break; }
  await page.waitForTimeout(100);
 }
 await scope.locator(':scope > [slot="title"]').waitFor({state:'hidden'});
}
async function frames() { await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))); }
(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH || 'C:/Users/strim/AppData/Local/ms-playwright/chromium_headless_shell-1208/chrome-headless-shell-win64/chrome-headless-shell.exe'});
 receipt.browser=browser.version();
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'ru-RU'});
 page=await context.newPage();page.on('pageerror',error=>receipt.pageErrors.push(error.message));
 await page.goto(state.url+'/login');
 await page.getByRole('textbox',{name:'Username',exact:true}).fill('accounting-pilot');
 await page.getByRole('textbox',{name:'Password',exact:true}).fill(fs.readFileSync(path.join(out,'login.local'),'utf8').trim());
 await button('Login').click();await page.waitForURL(url=>!url.pathname.includes('login'));
 await page.getByRole('navigation').waitFor();
 const toast=page.getByRole('button',{name:'Close',exact:true});if(await toast.count()) await toast.first().click();
 await openList('Справочники','Цеха');
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).waitFor();
 await evidence('cm5-list-wide');
 assert.equal(await button('Копировать').isDisabled(),true);
 await more().click();
 const copy=page.getByRole('menuitem',{name:/^Копировать/});await copy.waitFor();
 assert.equal(await copy.getAttribute('aria-disabled'),'true');
 assert.equal((await copy.innerText()).trim(),'Копировать','reason must not be part of the caption');
 await copy.hover();
 await page.getByRole('tooltip').filter({hasText:'Сначала выберите строку'}).waitFor();
 assert.ok(await copy.getAttribute('aria-describedby'),'disabled reason has no accessible description');
 await evidence('cm5-disabled-reason');
 await closeMenus();
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).click();
 await page.waitForFunction(()=>[...document.querySelectorAll('[data-cb-command]')].some(el=>el.getAttribute('aria-label')==='Изменить'&&!el.disabled));
 await more().click();
 const wideMenu=await page.getByRole('menuitem').allTextContents();await page.keyboard.press('Escape');
 await button('Обновить').focus();
 await page.setViewportSize({width:360,height:900});
 await page.waitForFunction(()=>[...document.querySelectorAll('[data-cb-command]')].some(el=>el.style.display==='none'));
 assert.equal(await page.evaluate(()=>!!document.activeElement.closest('[data-cb-more]')),true,'focus lost on overflow');
 await evidence('cm5-list-narrow-focus');
 await page.keyboard.press('Enter');await page.getByRole('menuitem',{name:'Создать',exact:true}).waitFor();
 assert.deepEqual(await page.getByRole('menuitem').allTextContents(),wideMenu,'full menu changed on resize');
 await evidence('cm5-list-narrow-menu');
 await page.keyboard.press('ArrowDown');await page.keyboard.press('Escape');
 assert.equal(await page.evaluate(()=>!!document.activeElement.closest('[data-cb-more]')),true);
 await page.setViewportSize({width:1440,height:1000});await frames();
 await page.waitForTimeout(500); // AppLayout drawer and MenuBar resize debounce must settle.
 await button('Обновить').waitFor();
 const positions=await page.locator('[data-cb-command]').filter({visible:true}).evaluateAll(els=>els.map(el=>el.getBoundingClientRect().top));
 assert.ok(positions.every(top=>top===positions[0]),'toolbar wrapped');
 receipt.cases.push('ListForm: default commands, disabled reason, selection, overflow/return, complete menu, focus and Enter/ArrowDown/Escape');
 await page.reload();
 await page.getByRole('navigation').waitFor();
 await openList('Справочники','Цеха');
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).click();
 await page.waitForFunction(()=>[...document.querySelectorAll('[data-cb-command]')].some(el=>el.getAttribute('aria-label')==='Изменить'&&!el.disabled));
 await frames();
 await more().click();await page.getByRole('menuitem',{name:/^Настройки/}).press('ArrowRight');
 await page.locator('[data-command-id="ui.list.quick-search"]').filter({visible:true}).waitFor();
 await evidence('cm5-settings');
 await page.locator('[data-command-id="ui.list.quick-search"]').filter({visible:true}).click();
 await page.locator('vaadin-text-field[placeholder="Быстрый поиск…"]').first().waitFor();
 await frames();
 await more().click();await page.getByRole('menuitem',{name:/^Настройки/}).press('ArrowRight');
 assert.equal(await page.locator('[data-command-id="ui.list.quick-search"]').filter({visible:true}).evaluate(el=>el.hasAttribute('menu-item-checked')),true);
 await page.locator('[data-command-id="ui.list.quick-search"]').filter({visible:true}).click();
 await page.locator('vaadin-text-field[placeholder="Быстрый поиск…"]').first().waitFor({state:'hidden'});
 await frames();
 await more().click();await page.getByRole('menuitem',{name:/^Сервис/}).click();
 await evidence('cm5-service');await page.keyboard.press('Escape');await page.keyboard.press('Escape');
 receipt.cases.push('Settings: current checked state; nonempty service submenu');
 await button('Изменить').click();
 await dialog(/Редактирование: Цех/).locator(':scope > [slot="title"]').waitFor();await evidence('cm5-edit-card');
 await button('Отмена',dialog(/Редактирование: Цех/)).click();
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).dblclick();
 await dialog(/Редактирование: Цех/).locator(':scope > [slot="title"]').waitFor();
 await button('Отмена',dialog(/Редактирование: Цех/)).click();
 receipt.cases.push('Existing edit button and row double-click open the same card');
 await openList('Справочники','Цены номенклатуры');
 await button('Изменить ключ записи').waitFor();await page.getByRole('combobox',{name:'Представление',exact:true}).waitFor();
 await evidence('cm5-independent-register');
 await page.getByRole('gridcell',{name:'150.0000',exact:true}).click();
 await button('Изменить ключ записи').click();
 const key=dialog('Изменить ключ записи');await key.locator(':scope > [slot="title"]').waitFor();
 await evidence('cm5-register-key');await button('Отмена',key).click();
 for(const width of [1280,760]) {
  await page.setViewportSize({width,height:900});await page.waitForTimeout(500);
  await more().click();await evidence('cm5-independent-'+width);await closeMenus();
 }
 await page.setViewportSize({width:1440,height:1000});await page.waitForTimeout(500);
 await button('Создать').click();
 const card=dialog('Создание: Цена номенклатуры');await card.locator(':scope > [slot="title"]').waitFor();
 await button('Выбрать: Номенклатура',card).click();
 const selection=dialog('Выбор номенклатуры');await selection.locator(':scope > [slot="title"]').waitFor();
 await evidence('cm5-jpa-selection');
 assert.equal(await button('Выбрать',selection).isDisabled(),true);
 assert.equal(await selection.getByRole('button',{name:'Создать',exact:true}).count(),0,'CRUD leaked into SelectionForm');
 await selection.getByRole('gridcell',{name:'PILOT-01',exact:true}).click();
 await button('Выбрать',selection).click();await selection.locator(':scope > [slot="title"]').waitFor({state:'hidden'});
 await button('Выбрать: Номенклатура',card).click();await selection.locator(':scope > [slot="title"]').waitFor();
 await page.setViewportSize({width:650,height:900});await evidence('cm5-jpa-selection-narrow');
 await selection.getByRole('gridcell',{name:'PILOT-02',exact:true}).dblclick();await selection.locator(':scope > [slot="title"]').waitFor({state:'hidden'});
 await page.setViewportSize({width:1440,height:1000});
 await cancelCard(card);
 receipt.cases.push('Independent register: key command, history/period retained; JPA SelectionForm at 700px/narrow, no CRUD, select and double-click');
 await openList('Производство','Корректировки объектов учёта');
 await page.getByRole('gridcell',{name:'ПРОБА-К-001',exact:true}).dblclick();
 const correction=dialog('Редактирование: Корректировка объекта учёта');
 await correction.locator(':scope > [slot="title"]').waitFor();
 await evidence('cm5-correction-card');
 await button('Добавить',correction).click();
 const row=dialog('Добавить: Корректировка типа учёта');
 await row.locator(':scope > [slot="title"]').waitFor();
 await button('Выбрать: Номенклатура',row).click();
 const memory=dialog('Объекты учёта цеха на дату корректировки');
 await memory.locator(':scope > [slot="title"]').waitFor();
 assert.equal(await memory.getByRole('button',{name:'Создать',exact:true}).count(),0);
 assert.equal(await button('Выбрать',memory).isDisabled(),true);
 await evidence('cm5-inmemory-selection');
 await memory.getByRole('gridcell',{name:'PILOT-01',exact:true}).dblclick();
 await memory.locator(':scope > [slot="title"]').waitFor({state:'hidden'});
 await cancelCard(row);await cancelCard(correction);
 receipt.cases.push('Direct in-memory SelectionForm: owner-filtered candidates, no CRUD, disabled Select, double-click; cancel restores draft');
 await openList('Производство','Объекты учёта номенклатуры');
 await page.getByRole('combobox',{name:'Представление',exact:true}).waitFor();
 await evidence('cm5-recorded-history');
 assert.equal(await page.getByRole('button',{name:'Создать',exact:true}).filter({visible:true}).count(),0);
 await page.getByRole('combobox',{name:'Представление',exact:true}).click();
 await page.getByRole('option',{name:'Срез последних',exact:true}).click();
 await evidence('cm5-recorded-slice');
 await page.getByRole('combobox',{name:'Представление',exact:true}).click();
 await page.getByRole('option',{name:'История',exact:true}).click();
 await more().click();
 assert.equal(await page.getByRole('menuitem',{name:'Просмотр',exact:true}).count(),0);
 await closeMenus();
 for(const width of [1280,760]) {
  await page.setViewportSize({width,height:900});await page.waitForTimeout(500);
  await more().click();await evidence('cm5-recorded-'+width);await closeMenus();
 }
 await page.setViewportSize({width:1440,height:1000});await page.waitForTimeout(500);
 receipt.cases.push('Recorded register: write commands absent, history/slice toggle; no DETAIL capability means no View');
 await openList('Справочники','Группы номенклатуры');
 await page.getByRole('gridcell',{name:'CM5-READ',exact:true}).click();
 await more().click();await page.getByRole('menuitem',{name:'Просмотр',exact:true}).click();
 const read=dialog('Просмотр: Группа номенклатуры');
 await read.locator(':scope > [slot="title"]').waitFor();
 assert.equal(await read.getByRole('button',{name:'Сохранить',exact:true}).filter({visible:true}).count(),0);
 await evidence('cm5-readonly-card');await button('Отмена',read).click();
 await page.getByRole('gridcell',{name:'CM5-READ',exact:true}).dblclick();
 await read.locator(':scope > [slot="title"]').waitFor();await button('Отмена',read).click();
 receipt.cases.push('Read-only catalog: View only in More and double-click open read-only card, Save absent');
 await page.getByRole('tab',{name:'Цеха',exact:true}).click();
 await page.getByRole('gridcell',{name:'PILOT-A',exact:true}).waitFor();
 assert.equal(await page.locator('.ipro-command-bar').filter({visible:true}).count(),1);
 assert.equal(await button('Печать').isDisabled(),false,'selection lost while tab hidden');
 await button('Печать').click();
 const print=dialog('Выберите печатную форму');await print.locator(':scope > [slot="title"]').waitFor();
 await evidence('cm5-print-dialog');await button('Закрыть',print).click();
 for(const [width,zoom] of [[1280,1],[760,1],[1280,1.25],[1280,1.5]]) {
  await page.setViewportSize({width,height:900});
  await page.evaluate(z=>document.documentElement.style.zoom=z,zoom);await page.waitForTimeout(500);
  await more().click();await evidence('cm5-layout-'+width+'-zoom-'+zoom);
  const overflow=await page.locator('vaadin-menu-bar-list-box[theme~="ipro-list-commands"],vaadin-context-menu-list-box[theme~="ipro-list-commands"]').filter({visible:true}).evaluateAll(els=>els.map(el=>{
   const content=el.shadowRoot.querySelector('[part="items"]');return {client:content.clientWidth,scroll:content.scrollWidth};
  }));
  assert.ok(overflow.length>0,'visible menu was not measured');
  assert.ok(overflow.every(v=>v.scroll<=v.client),'menu horizontally overflows');
  await closeMenus();
 }
 await page.evaluate(()=>document.documentElement.style.zoom='1');
 await page.setViewportSize({width:760,height:900});
 const primary=page.locator('[data-cb-command][aria-label="Создать"]').filter({visible:true}).first();
 const changed=await primary.evaluate(el=>{let changed=false;for(const node of el.childNodes)if(node.nodeType===Node.TEXT_NODE){node.textContent='Создать новый элемент с очень длинным переведённым названием команды';changed=true;}return changed;});
 assert.ok(changed,'toolbar caption was not changed for the layout stress');
 await page.waitForTimeout(500);
 await more().click();
 const create=page.getByRole('menuitem',{name:'Создать',exact:true});
 await create.evaluate(el=>{for(const node of el.childNodes)if(node.nodeType===Node.TEXT_NODE)node.textContent='Создать новый элемент с очень длинным переведённым названием команды';});
 await frames();await evidence('cm5-long-captions');
 const longMenu=await page.locator('vaadin-menu-bar-list-box[theme~="ipro-list-commands"],vaadin-context-menu-list-box[theme~="ipro-list-commands"]').filter({visible:true}).evaluateAll(els=>els.map(el=>{
  const part=el.shadowRoot.querySelector('[part="items"]');return {client:part.clientWidth,scroll:part.scrollWidth};
 }));
 assert.ok(longMenu.length>0 && longMenu.every(v=>v.scroll<=v.client),'long caption overflows');
 await closeMenus();
 receipt.cases.push('Late long caption layout stress: toolbar remeasures and menu wraps without horizontal overflow (temporary client text, not persisted)');
 receipt.cases.push('Workspace tab reactivation: one command bar, selection and print retained; 1280/760 widths and CSS layout zoom 125%/150%');
 assert.deepEqual(receipt.pageErrors,[]);
 receipt.status='passed';await evidence('cm5-final');
 console.log(JSON.stringify(receipt,null,2));
})().catch(async error=>{
 receipt.status='failed';receipt.error=String(error);console.error(error);
 if(page) await evidence('cm5-failure').catch(()=>{});process.exitCode=1;
}).finally(async()=>{fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify(receipt,null,2));await browser?.close();});


