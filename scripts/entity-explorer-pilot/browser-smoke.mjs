// Run through the supported browser-use Tab API (CUA), against a signed-in local pilot.
// No second browser controller, injected app state or database access.
function check(condition, message) { if (!condition) throw new Error(message); }

export async function smokeName(tab) {
  await tab.playwright.getByRole('button', {name: 'Данные', exact: true}).click();
  await tab.playwright.locator('[data-field="name"]').click();
  await tab.playwright.locator('[data-inspector-field="name"]').waitFor({state: 'visible'});
  let text = await tab.playwright.getByRole('region', {name: 'Свойства выбранного реквизита'}).innerText();
  check(text.includes('До 200 (@Size)') && text.includes('Значение обязательно'), 'name lost its size/write facts');
  check(text.includes('По метаданным') && text.includes('позиция 2'), 'name lost its applications');
  const address = await tab.url();
  check(!address.includes('?'), 'Local field selection added a URL anchor/query');
  await tab.playwright.locator('[data-field="name"]').press('ArrowDown');
  await tab.playwright.locator('[data-inspector-field="unitOfMeasurement"]').waitFor({state:'visible'});
  await tab.playwright.getByText('Источники и хранение', {exact:true}).click();
  await tab.playwright.locator('[data-field="name"]').click();
  text = await tab.playwright.getByRole('region', {name:'Свойства выбранного реквизита'}).innerText();
  check(text.includes('Bean Validation') && text.includes('JPA length') && text.includes('255'), 'Selected-field sources/storage were lost');
  await tab.playwright.getByRole('button', {name:'Техническая сводка', exact:true}).click();
  await tab.playwright.getByRole('button', {name:'К компактной карточке', exact:true}).waitFor({state:'visible'});
  await tab.playwright.waitForURL(address + '?view=overview');
  check((await tab.url()).endsWith('?view=overview'), 'Technical summary failed to use its legacy anchor');
  await tab.playwright.getByRole('button', {name:'К компактной карточке', exact:true}).click();
  await tab.playwright.locator('[data-inspector-field="name"]').waitFor({state:'visible'});
  await tab.playwright.waitForURL(address);
  check((await tab.url()) === address, 'Returning to pilot failed to clear the legacy anchor');
  text = await tab.playwright.getByRole('region', {name:'Свойства выбранного реквизита'}).innerText();
  check(text.includes('JPA length'), 'Source disclosure did not survive technical-summary round trip');
  await tab.playwright.getByText('Источники и хранение', {exact:true}).click();
  return {scenario:'name', address, facts:true, keyboard:true, technicalRoundTrip:true, sourcesRetained:true};
}

export async function smokeDensity(tab, expectedRows = 30) {
  await tab.playwright.getByRole('button', {name:'Данные', exact:true}).click();
  await tab.playwright.locator('[data-field]').first().press('Home');
  await tab.playwright.locator('[data-inspector-field="code"]').waitFor({state:'visible'});
  const metrics = await tab.playwright.evaluate(() => {
    const pilot = document.querySelector('.entity-explorer-pilot');
    const scroll = pilot.querySelector('.explorer-field-scroll');
    const bounds = scroll.getBoundingClientRect();
    const header = pilot.querySelector('.explorer-field-columns').getBoundingClientRect();
    const rows = [...pilot.querySelectorAll('[data-field]')].map(e => {
      const r = e.getBoundingClientRect();
      return {name:e.getAttribute('data-field'), top:r.top, bottom:r.bottom, height:r.height};
    });
    const overflows = [];
    for (let e = pilot; e && e !== document.body; e = e.parentElement) {
      if (e.clientWidth && e.scrollWidth > e.clientWidth + 1) overflows.push({tag:e.tagName, classes:e.className, extra:e.scrollWidth-e.clientWidth});
      if (e.classList.contains('workspace-content')) break;
    }
    return {width:innerWidth,height:innerHeight,scale:visualViewport.scale, rows:rows.length,
      navWidth:pilot.querySelector('.explorer-pilot-nav').getBoundingClientRect().width,
      inspectorWidth:pilot.querySelector('.explorer-field-inspector').getBoundingClientRect().width,
      rowHeights:[...new Set(rows.map(r=>r.height))],
      completeRows:rows.filter(r=>r.top >= Math.max(bounds.top,header.bottom)-.5 && r.bottom <= bounds.bottom+.5).length,
      overflows, documentWidth:document.documentElement.scrollWidth, pilotRight:pilot.getBoundingClientRect().right};
  });
  check(metrics.rows === expectedRows, `Expected ${expectedRows} rows; got ${metrics.rows}`);
  check(metrics.completeRows >= 15, `Only ${metrics.completeRows} complete rows visible`);
  check(metrics.rowHeights.every(h=>h>=24 && h<=28), 'Row density left the 24–28 px contract');
  check(metrics.navWidth >= 150 && metrics.navWidth <= 180, 'Section rail width left its desktop range');
  check(metrics.inspectorWidth >= 300 && metrics.inspectorWidth <= 360, 'Initial inspector width left its desktop range');
  check(metrics.overflows.length === 0 && metrics.pilotRight <= metrics.width+1 && metrics.documentWidth <= metrics.width+1, 'Horizontal overflow in pilot/host');
  await tab.playwright.locator('[data-field="code"]').press('End');
  // Since P3 the collapsed section follows root fields in keyboard navigation.
  const hasSections = await tab.playwright.locator('[data-section]').count() > 0;
  await tab.playwright.locator(hasSections ? '[data-inspector-section]' : '[data-inspector-field="field30"]').waitFor({state:'visible'});
  await tab.playwright.locator('.explorer-field-scroll [aria-selected="true"]').press('Home');
  await tab.playwright.locator('[data-inspector-field="code"]').waitFor({state:'visible'});
  await tab.playwright.locator('[data-field="code"]').press('ArrowDown');
  await tab.playwright.locator('[data-inspector-field="name"]').waitFor({state:'visible'});
  return {...metrics, keyboardHomeEnd:true};
}
