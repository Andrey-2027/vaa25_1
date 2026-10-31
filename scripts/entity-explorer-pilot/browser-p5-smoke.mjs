// Run through the supported CUA Tab API on a signed-in isolated stand.
function check(value, message) { if (!value) throw new Error(message); }

export async function simplePages(tab, capture = async () => {}) {
  const results = [];
  for (const page of ['general', 'presentation', 'numbering', 'owners']) {
    await tab.playwright.locator(`[data-tab="${page}"]`).click();
    await tab.playwright.locator(`[data-tab="${page}"][aria-pressed="true"]`).waitFor({state:'visible'});
    const metrics = await tab.playwright.evaluate(() => {
      const pilot = document.querySelector('.entity-explorer-pilot');
      const page = [...pilot.querySelector('.explorer-pilot-pages').children]
        .find(element => element.getBoundingClientRect().height > 0);
      return {viewport:[innerWidth, innerHeight], scale:visualViewport.scale,
        overflow:pilot.scrollWidth > pilot.clientWidth + 1,
        split:page.querySelector('vaadin-split-layout') !== null,
        rows:[...page.querySelectorAll('.explorer-property')]
          .map(element => element.getBoundingClientRect().height).filter(height => height > 0)};
    });
    check(!metrics.overflow && !metrics.split, 'Simple page has overflow or empty inspector');
    await capture(page === 'owners' ? 'w5' : `w4-${page}`);
    results.push({page, ...metrics});
  }
  return results;
}

export async function narrowPage(tab, field, capture = async () => {}) {
  await tab.playwright.locator('[data-tab="data"]').click();
  const split = tab.playwright.locator('.explorer-data-split[data-narrow]');
  await split.waitFor({state:'visible'});
  const back = tab.playwright.getByRole('button', {name:'Назад к списку', exact:true});
  if (await back.isVisible()) await back.click();
  const row = tab.playwright.locator(`[data-context="root"][data-field="${field}"]`);
  await row.click();
  await back.waitFor({state:'visible'});
  check(!(await row.isVisible()), 'List still visible beside narrow inspector');
  await capture('w8-inspector');
  await back.click();
  await row.waitFor({state:'visible'});
  check(await row.getAttribute('aria-selected') === 'true', 'Selection lost on return');
  await capture('w8-list');
  // A second selection catches stale server/client detail state after Back.
  await row.press('Enter');
  await back.waitFor({state:'visible'});
  await back.click();
  const geometry = await tab.playwright.evaluate(() => {
    const pilot = document.querySelector('.entity-explorer-pilot');
    return {viewport:[innerWidth,innerHeight],scale:visualViewport.scale,
      overflow:pilot.scrollWidth > pilot.clientWidth + 1,
      scrollTop:pilot.querySelector('.explorer-field-scroll').scrollTop};
  });
  check(!geometry.overflow, 'Narrow card overflows');
  return {...geometry, field, selectionRestored:true, repeatedSelection:true};
}

// Use a short viewport (e.g. 600×430) and an expanded section with this row.
export async function scrolledReturn(tab, field) {
  const row = tab.playwright.locator(`.explorer-section-fields [data-field="${field}"]`);
  await row.press('Shift'); // Focus scrolls the off-screen row without selecting it.
  const before = await tab.playwright.locator('.explorer-field-scroll').evaluate(element => element.scrollTop);
  check(before > 0, 'The scroll test must exercise a nonzero offset');
  await row.press('Enter');
  await tab.playwright.getByRole('button', {name:'Назад к списку', exact:true}).click();
  const after = await tab.playwright.locator('.explorer-field-scroll').evaluate(element => element.scrollTop);
  check(before === after && await row.getAttribute('aria-selected') === 'true', 'Scroll/selection lost');
  return {before, after, field, selectionRestored:true};
}
