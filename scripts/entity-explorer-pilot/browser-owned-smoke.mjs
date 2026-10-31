// Supported CUA Tab API, signed-in isolated pilot. No business-data reads or injected app state.
function check(condition, message) { if (!condition) throw new Error(message); }

export async function smokeOwned(tab, fieldName, rowName, capture = async () => {}) {
  await tab.playwright.locator('[data-tab="data"]').click();
  const toggle = tab.playwright.locator('[data-section-toggle]');
  const section = tab.playwright.locator('[data-section]');
  const field = tab.playwright.locator('.explorer-section-fields [data-field="' + fieldName + '"]');
  if (await toggle.getAttribute('aria-expanded') === 'true') await toggle.click();
  await section.click();
  await tab.playwright.locator('[data-inspector-section]').waitFor({state:'visible'});
  check(await toggle.getAttribute('aria-expanded') === 'false', 'Selecting a section expanded it');
  await toggle.click();
  await field.click();
  await tab.playwright.locator('[data-inspector-field="' + fieldName + '"]').waitFor({state:'visible'});
  check(await field.getAttribute('aria-selected') === 'true', 'Owned field is not selected');
  const metrics = await tab.playwright.evaluate(() => {
    const pilot = document.querySelector('.entity-explorer-pilot');
    const rows = [...pilot.querySelectorAll('.explorer-section-fields [data-field]')];
    const bounds = pilot.querySelector('.explorer-field-scroll').getBoundingClientRect();
    return {
      viewport: [innerWidth, innerHeight], scale: visualViewport.scale,
      rowHeights: rows.map(e => e.getBoundingClientRect().height),
      fullyVisibleRows: rows.filter(e => { const r = e.getBoundingClientRect(); return r.top >= bounds.top && r.bottom <= bounds.bottom; }).length,
      structuralRows: rows.length,
      overflow: [pilot, document.documentElement].some(e => e.scrollWidth > e.clientWidth + 1),
      breadcrumb: pilot.querySelector('.explorer-pilot-breadcrumb').textContent
    };
  });
  check(!metrics.overflow && metrics.rowHeights.every(h => h >= 24 && h <= 28), 'Owned list density/overflow failed');
  check(metrics.fullyVisibleRows === metrics.structuralRows, 'Pilot owned fields do not fit');
  await capture('w2');
  await toggle.click();
  await tab.playwright.locator('[data-inspector-section]').waitFor({state:'visible'});
  check(await section.getAttribute('aria-selected') === 'true', 'Collapse did not select section');
  // Vaadin defers DOM updates inside server-hidden subtrees until they are shown again.
  check(!(await field.isVisible()), 'Collapsed child remains visible');
  await capture('w3');
  await toggle.click();
  await tab.playwright.locator('[data-inspector-field="' + fieldName + '"]').waitFor({state:'visible'});
  const address = await tab.url();
  await tab.playwright.getByRole('button',{name:'Техническая сводка',exact:true}).click();
  await tab.playwright.waitForURL(address + '?view=overview');
  await tab.playwright.getByRole('button',{name:'К компактной карточке',exact:true}).waitFor({state:'visible'});
  await tab.playwright.getByRole('button',{name:'К компактной карточке',exact:true}).click();
  await tab.playwright.waitForURL(address);
  await tab.playwright.locator('[data-inspector-field="' + fieldName + '"]').waitFor({state:'visible'});
  await tab.playwright.locator('[data-tab="owners"]').click();
  await tab.playwright.getByRole('heading',{name:'Родитель / Владельцы',exact:true}).waitFor({state:'visible'});
  check((await tab.playwright.locator('.explorer-pilot-pages').innerText()).includes('Явный контракт не подключён'), 'Missing hierarchy contract was hidden');
  await capture('w5');
  await tab.playwright.getByRole('button',{name:rowName,exact:true}).click();
  await tab.playwright.locator('[data-inspector-section]').waitFor({state:'visible'});
  check(!(await tab.url()).includes('?'), 'Local owned navigation changed URL grammar');
  return {...metrics, collapseRestoration:true, technicalRoundTrip:true, ownerNavigation:true};
}
