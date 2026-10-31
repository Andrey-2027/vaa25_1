// Run via the supported CUA Tab API against an already signed-in isolated pilot.
function check(value, message) { if (!value) throw new Error(message); }
async function chooseRegistration(tab, key) {
  const row = tab.playwright.locator(`[data-behavior-choice="${key}"]`);
  if (!(await row.isVisible())) {
    await tab.playwright.getByRole('button', {name:'Назад к списку',exact:true}).press('Enter');
    await row.waitFor({state:'visible'});
  }
  await row.press('Enter');
  await tab.playwright.locator(`[data-behavior-choice="${key}"][aria-pressed="true"]`).waitFor({state:'attached'});
}

export async function previewSmoke(tab, entity, capture = async () => {}) {
  await tab.playwright.locator('[data-tab="forms"]').click();
  await chooseRegistration(tab, 'ITEM/default');
  const before = await tab.url();
  const preview = tab.playwright.getByRole('button', {name:'Предпросмотр',exact:true});
  check(await preview.isEnabled(), 'Supported registration has no preview');
  await preview.click();
  await tab.playwright.locator('[data-preview-mode="empty"]').waitFor({state:'attached'});
  const grid = tab.playwright.locator('.explorer-preview-table').getByRole('row');
  await grid.first().waitFor({state:'visible'});
  check(await grid.count() === 1, 'Empty section contains rows or has no columns');
  await capture(`w9-${entity}-empty`);
  await tab.playwright.getByRole('radio', {name:'С примером',exact:true}).check();
  await tab.playwright.locator('[data-preview-mode="example"]').waitFor({state:'attached'});
  const expected = entity === 'nomenclature' ? 2 : 3; // header + local rows
  await grid.nth(expected-1).waitFor({state:'visible'});
  check(await grid.count() === expected, 'Example section row count differs');
  await capture(`w9-${entity}-example`);
  const geometry = await tab.playwright.locator('.explorer-preview-form').evaluate(e => ({
    width:e.clientWidth, scrollWidth:e.scrollWidth, viewport:[innerWidth,innerHeight],
    scale:visualViewport.scale,
  }));
  check(geometry.scrollWidth <= geometry.width + 1, 'Form overflows the dialog');
  await tab.playwright.getByRole('radio', {name:'Пустая',exact:true}).check();
  await tab.playwright.locator('[data-preview-mode="empty"]').waitFor({state:'attached'});
  await grid.first().waitFor({state:'visible'});
  check(await grid.count() === 1, 'Example rows survived reset');
  await tab.playwright.getByRole('button', {name:'Закрыть',exact:true}).click();
  await tab.playwright.locator('.explorer-preview-form').waitFor({state:'detached'});
  check(await tab.url() === before, 'Preview changed URL');
  check(await tab.playwright.locator('[data-behavior-choice="ITEM/default"]').getAttribute('aria-pressed') === 'true',
    'Preview changed the selected registration');
  await preview.press('Enter');
  await tab.playwright.locator('[data-preview-mode="empty"]').waitFor({state:'attached'});
  await grid.first().waitFor({state:'visible'});
  check(await grid.count() === 1, 'Reopened preview retained examples');
  await tab.playwright.getByRole('button', {name:'Закрыть',exact:true}).click();
  await tab.playwright.locator('.explorer-preview-form').waitFor({state:'detached'});
  await chooseRegistration(tab, 'LIST/default');
  check(!(await preview.isEnabled()), 'Unsupported registration permits preview');
  await chooseRegistration(tab, 'ITEM/default');
  return {entity,...geometry, emptyRows:0, exampleRows:expected-1, reset:true,reopen:true,
    urlPreserved:true,registrationPreserved:true,unsupportedDisabled:true};
}
