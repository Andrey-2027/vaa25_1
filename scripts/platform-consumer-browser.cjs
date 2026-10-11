// Run against the owned PostgreSQL run created by test-platform-consumer-postgresql.ps1.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const run = path.resolve(process.argv[2] || '');
const phase = process.argv[3] || 'first';
if (!['first', 'restarted'].includes(phase)) throw new Error('Phase: first | restarted');
const read = name => JSON.parse(fs.readFileSync(path.join(run, name), 'utf8').replace(/^\uFEFF/, ''));
const state = read('state.json');
if (state.owner !== 'platform-consumer-p4' || !/^http:\/\/127\.0\.0\.1:\d+$/.test(state.url)) throw new Error('Expected owned loopback P4 run');
if (!process.env.CONSUMER_DEMO_PASSWORD) throw new Error('Set CONSUMER_DEMO_PASSWORD');
const receipt = { phase, url: state.url, cases: [], pageErrors: [] };
let browser;
const pages = [];
const button = (scope, name) => scope.getByRole('button', { name, exact: true }).filter({ visible: true }).first();
const card = page => page.getByRole('dialog', { name: /^(Создание|Редактирование|Просмотр): Заявка на материалы$/ });
async function evidence(page, name) {
  fs.writeFileSync(path.join(run, `${phase}-${name}.aria.txt`), await page.locator('body').ariaSnapshot());
  await page.screenshot({ path: path.join(run, `${phase}-${name}.png`), fullPage: true });
}
async function login(user) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'ru-RU' });
  const page = await context.newPage(); pages.push(page);
  page.on('pageerror', e => receipt.pageErrors.push(String(e)));
  await page.goto(state.url + '/login');
  await page.getByRole('textbox', { name: 'Username', exact: true }).fill(user);
  await page.getByRole('textbox', { name: 'Password', exact: true }).fill(process.env.CONSUMER_DEMO_PASSWORD);
  await button(page, 'Log in').click();
  await button(page, 'Заявки').click();
  await page.getByRole('gridcell', { name: user === 'alice' ? 'FIXTURE-1' : 'FIXTURE-2', exact: true }).waitFor();
  return page;
}
async function row(page, search, material, quantity) {
  await button(card(page), 'Добавить').click();
  const d = page.getByRole('dialog', { name: 'Добавить: Строка заявки', exact: true });
  await d.getByRole('textbox').first().fill(search);
  await d.getByRole('gridcell', { name: material, exact: true }).click();
  await d.getByRole('textbox', { name: 'Количество', exact: true }).fill(quantity);
  return d;
}
async function open(page, number) {
  await page.getByRole('gridcell', { name: number, exact: true }).dblclick();
  await card(page).getByRole('textbox', { name: 'Комментарий', exact: true }).waitFor();
}
async function save(page) {
  await button(card(page), 'Сохранить').click();
  await card(page).getByRole('textbox', { name: 'Комментарий', exact: true }).waitFor({ state: 'hidden' });
}
async function numbers(page) {
  return [...(await page.locator('body').ariaSnapshot()).matchAll(/gridcell "(ЗМ-\d{6})"/g)].map(m => m[1]);
}
async function addedNumber(page, previous) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const added = (await numbers(page)).find(n => !previous.includes(n));
    if (added) return added;
    await page.waitForTimeout(100);
  }
  throw new Error('Saved numbered request did not appear in list');
}
async function create(page, comment) {
  await button(page, 'Создать').click();
  assert.equal(await card(page).getByRole('textbox', { name: 'Номер', exact: true }).inputValue(), '');
  assert.equal(await card(page).getByRole('textbox', { name: 'Подразделение (1 или 2)', exact: true }).inputValue(), '1');
  await card(page).getByRole('textbox', { name: 'Комментарий', exact: true }).fill(comment);
}
async function main() {
  browser = await chromium.launch({ headless: true, channel: process.env.CONSUMER_BROWSER_CHANNEL || 'chrome' });
  receipt.browser = browser.version();
  const alice = await login('alice');
  assert.equal(await alice.getByRole('gridcell', { name: 'FIXTURE-2', exact: true }).count(), 0);
  receipt.cases.push('Alice login and department RLS');
  if (phase === 'first') {
    await button(alice, 'Материалы').click();
    await alice.getByRole('gridcell', { name: 'MAT-001', exact: true }).waitFor();
    await alice.getByRole('gridcell', { name: 'MAT-002', exact: true }).waitFor();
    await button(alice, 'Заявки').click();
    await alice.getByRole('gridcell', { name: 'FIXTURE-1', exact: true }).waitFor();
    receipt.cases.push('Independent materials list');
    const previousNumbers = await numbers(alice);
    const comment = 'P4 browser ' + state.runId;
    await create(alice, comment);
    await button(card(alice), 'Сохранить').click();
    await alice.getByRole('alert').filter({ hasText: 'должна содержать минимум 1 строк' }).waitFor();
    await evidence(alice, 'empty-section');
    receipt.cases.push('Empty section rejected, form retained');
    let d = await row(alice, 'Бу', 'MAT-001 — Бумага', '0');
    await button(d, 'Сохранить').click();
    await d.getByRole('textbox', { name: 'Количество', exact: true }).waitFor({ state: 'hidden' });
    await card(alice).getByRole('row', { name: 'MAT-001 — Бумага 0', exact: true }).waitFor();
    await button(card(alice), 'Сохранить').click();
    await alice.getByRole('alert').filter({ hasText: /больше|greater|положитель|0/ }).waitFor();
    await evidence(alice, 'invalid-quantity');
    await card(alice).getByRole('row', { name: 'MAT-001 — Бумага 0', exact: true }).click();
    await button(card(alice), 'Изменить').click();
    d = alice.getByRole('dialog', { name: 'Изменить: Строка заявки', exact: true });
    await d.getByRole('textbox', { name: 'Количество', exact: true }).fill('2');
    await button(d, 'Сохранить').click(); await d.getByRole('textbox', { name: 'Количество', exact: true }).waitFor({ state: 'hidden' });
    receipt.cases.push('Lookup and positive quantity validation corrected in same row form');
    d = await row(alice, 'Бу', 'MAT-001 — Бумага', '3');
    await button(d, 'Сохранить').click(); await d.getByRole('textbox', { name: 'Количество', exact: true }).waitFor({ state: 'hidden' });
    await button(card(alice), 'Сохранить').click();
    await alice.getByRole('alert').filter({ hasText: 'Материал не должен повторяться' }).waitFor();
    await evidence(alice, 'lifecycle-veto');
    receipt.cases.push('Duplicate material lifecycle veto, form retained');
    await card(alice).getByRole('row', { name: 'MAT-001 — Бумага 3', exact: true }).click();
    await button(card(alice), 'Удалить').click();
    d = await row(alice, 'Кар', 'MAT-002 — Картридж', '3');
    await button(d, 'Сохранить').click(); await d.getByRole('textbox', { name: 'Количество', exact: true }).waitFor({ state: 'hidden' });
    await save(alice);
    const number = await addedNumber(alice, previousNumbers);
    assert.ok(number, 'Number assigned after save'); receipt.number = number; receipt.comment = comment;
    await open(alice, number);
    assert.equal(await card(alice).getByRole('textbox', { name: 'Комментарий', exact: true }).inputValue(), comment);
    await card(alice).getByRole('row', { name: /^MAT-001 — Бумага 2(?:[.]000)?$/ }).waitFor();
    await card(alice).getByRole('row', { name: /^MAT-002 — Картридж 3(?:[.]000)?$/ }).waitFor();
    assert.ok(await card(alice).getByRole('textbox', { name: 'Номер', exact: true }).getAttribute('readonly') !== null);
    await evidence(alice, 'saved-aggregate');
    receipt.cases.push('Full aggregate persisted and reopened, assigned number read-only');
    const second = await login('alice'); await open(second, number);
    receipt.comment = comment + ' winner';
    await card(alice).getByRole('textbox', { name: 'Комментарий', exact: true }).fill(receipt.comment);
    await save(alice);
    await card(second).getByRole('textbox', { name: 'Комментарий', exact: true }).fill(comment + ' losing edit');
    await button(card(second), 'Сохранить').click();
    await second.getByRole('alert').filter({ hasText: 'Запись изменена другим пользователем' }).waitFor();
    await evidence(second, 'conflict');
    assert.equal(await card(second).getByRole('textbox', { name: 'Комментарий', exact: true }).inputValue(), comment + ' losing edit');
    receipt.cases.push('Concurrent browser edit rejected with input retained');
    await open(alice, number);
    assert.equal(await card(alice).getByRole('textbox', { name: 'Комментарий', exact: true }).inputValue(), receipt.comment);
    await button(card(alice), 'Отмена').click();
    const bob = await login('bob');
    assert.equal(await bob.getByRole('gridcell', { name: number, exact: true }).count(), 0);
    assert.equal(await bob.getByRole('gridcell', { name: 'FIXTURE-1', exact: true }).count(), 0);
    assert.ok(await button(bob, 'Создать').isDisabled());
    assert.ok(await button(bob, 'Удалить').isDisabled());
    await open(bob, 'FIXTURE-2');
    assert.ok(await card(bob).getByRole('textbox', { name: 'Комментарий', exact: true }).isDisabled());
    assert.ok(await button(card(bob), 'Добавить').isDisabled());
    assert.ok(await button(card(bob), 'Изменить').isDisabled());
    assert.ok(await button(card(bob), 'Удалить').isDisabled());
    assert.equal(await card(bob).getByRole('button', { name: 'Сохранить', exact: true }).count(), 0);
    await evidence(bob, 'reader');
    receipt.cases.push('Bob department RLS and reader card');
  } else {
    const first = read('browser-first.json'); receipt.number = first.number;
    await open(alice, first.number);
    assert.equal(await card(alice).getByRole('textbox', { name: 'Комментарий', exact: true }).inputValue(), first.comment);
    await card(alice).getByRole('row', { name: /^MAT-001 — Бумага 2(?:[.]000)?$/ }).waitFor();
    await card(alice).getByRole('row', { name: /^MAT-002 — Картридж 3(?:[.]000)?$/ }).waitFor();
    await evidence(alice, 'persisted-aggregate');
    await button(card(alice), 'Отмена').click();
    receipt.cases.push('Restart: saved header, rows, quantities and number retained');
    const previousNumbers = await numbers(alice);
    await create(alice, 'P4 after restart ' + state.runId);
    const d = await row(alice, 'Бу', 'MAT-001 — Бумага', '1');
    await button(d, 'Сохранить').click(); await d.getByRole('textbox', { name: 'Количество', exact: true }).waitFor({ state: 'hidden' }); await save(alice);
    receipt.nextNumber = await addedNumber(alice, previousNumbers);
    assert.ok((await numbers(alice)).includes(first.number));
    assert.ok(Number(receipt.nextNumber?.slice(3)) > Number(first.number.slice(3)));
    receipt.cases.push('Restart: numbering continues without reusing previous number');
  }
  assert.deepEqual(receipt.pageErrors, []);
  receipt.status = 'passed';
}
main().catch(async error => {
  receipt.status = 'failed'; receipt.error = String(error); console.error(error);
  for (let i = 0; i < pages.length; i++) await evidence(pages[i], `failure-${i}`).catch(() => {});
  process.exitCode = 1;
}).finally(async () => {
  fs.writeFileSync(path.join(run, `browser-${phase}.json`), JSON.stringify(receipt, null, 2));
  await browser?.close(); console.log(JSON.stringify(receipt, null, 2));
});


