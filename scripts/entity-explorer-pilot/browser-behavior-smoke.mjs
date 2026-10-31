function check(value, message) { if (!value) throw new Error(message); }

// Run via the supported CUA Tab API on a signed-in, isolated pilot stand.
export async function smokeBehavior(tab, expected, capture = async () => {}) {
  const address = await tab.url();
  await tab.playwright.locator('[data-tab="forms"]').click();
  await tab.playwright.locator('[data-behavior-choice="ITEM/default"]').click();
  await tab.playwright.getByRole('button', {name:'Код регистрации',exact:true}).waitFor({state:'visible'});
  const properties = await tab.playwright.locator('.explorer-behavior-detail').filter({visible:true}).innerText();
  check(properties.includes(expected.formClass.split('.').at(-1)) && properties.includes(expected.config.split('.').at(-1)), 'Declared class/config lost');
  check(properties.includes('Генератор не подключён') && properties.includes('Результирующий файл'), 'Generation slots lost');
  await capture('w6');
  if (expected.formClass !== 'org.ipro.form.builtin.ItemForm') {
    await tab.playwright.getByRole('button',{name:'Код класса',exact:true}).click();
    await tab.playwright.locator('[data-source-symbol="'+expected.formClass+'"]').waitFor({state:'visible'});
    check((await tab.playwright.locator('.explorer-form-source-page').innerText()).includes('Источник: рабочая копия'), 'Form class source unavailable');
    await tab.playwright.getByRole('button',{name:'Назад к формам',exact:true}).click();
  } else check(await tab.playwright.getByRole('button',{name:'Код класса',exact:true}).count() === 0, 'Platform class source should not be an application action');
  await tab.playwright.locator('[data-behavior-choice="ITEM/default"]').waitFor({state:'visible'});
  check(await tab.playwright.locator('[data-behavior-choice="ITEM/default"]').getAttribute('aria-pressed') === 'true', 'Form selection lost');
  await tab.playwright.getByRole('button',{name:'Код регистрации',exact:true}).click();
  await tab.playwright.locator('[data-source-symbol="'+expected.config+'"]').waitFor({state:'visible'});
  check((await tab.playwright.locator('.explorer-form-source-page').innerText()).includes('variants.addDefault'), 'Registration source is not real');
  await capture('w6-source');
  await tab.playwright.getByRole('button',{name:'Назад к формам',exact:true}).click();
  await tab.playwright.locator('[data-tab="lifecycle"]').click();
  await tab.playwright.locator('[data-behavior-choice="beforeAggregateSave"]').click();
  await tab.playwright.locator('[data-source-symbol="'+expected.handler+'#beforeAggregateSave"]').waitFor({state:'visible'});
  const code = await tab.playwright.locator('.explorer-behavior-detail').filter({visible:true}).innerText();
  check(code.includes(expected.codeFragment) && code.includes('соответствие запущенному bytecode не подтверждено'), 'Real lifecycle code/source note missing');
  const readMetrics = () => tab.playwright.evaluate(() => {
    const pilot = document.querySelector('.entity-explorer-pilot');
    const page = [...pilot.querySelectorAll('.explorer-behavior-page')].find(p=>p.getBoundingClientRect().height>0);
    const code = page.querySelector('.explorer-source-code');
    const first = page.querySelector('.explorer-source-focus');
    const cb=code.getBoundingClientRect(), fb=first.getBoundingClientRect();
    return {viewport:[innerWidth,innerHeight],scale:visualViewport.scale,
      rowHeights:[...page.querySelectorAll('.explorer-behavior-row')].map(e=>e.getBoundingClientRect().height),
      highlightedLines:page.querySelectorAll('.explorer-source-focus').length,
      focusOffset:fb.top-cb.top,codeHeight:cb.height,
      overflow:[pilot,document.documentElement].some(e=>e.scrollWidth>e.clientWidth+1)};
  });
  let metrics = await readMetrics();
  const focusDeadline = Date.now() + 2000;
  while ((metrics.focusOffset<0 || metrics.focusOffset>40) && Date.now()<focusDeadline) metrics = await readMetrics();
  check(!metrics.overflow && metrics.rowHeights.length===6 && metrics.rowHeights.every(h=>h>=24&&h<=28), 'Lifecycle density/overflow failed');
  check(metrics.highlightedLines>0 && metrics.focusOffset>=0 && metrics.focusOffset<=40, 'Selected method is not focused at top of code: '+JSON.stringify(metrics));
  await capture('w7');
  await tab.playwright.locator('[data-behavior-choice="afterCommit"]').click();
  await tab.playwright.locator('[data-source-symbol="org.ipro.lifecycle.EntityLifecycle#afterCommit"]').waitFor({state:'visible'});
  check((await tab.playwright.locator('.explorer-behavior-detail').filter({visible:true}).innerText()).includes('Default контракта'), 'Default hook status lost');
  await capture('w7-default');
  await tab.playwright.locator('[data-behavior-choice="beforeAggregateSave"]').click();
  await tab.playwright.getByRole('button',{name:'Техническая сводка',exact:true}).click();
  await tab.playwright.waitForURL(address+'?view=overview');
  await tab.playwright.getByRole('button',{name:'К компактной карточке',exact:true}).click();
  await tab.playwright.waitForURL(address);
  await tab.playwright.locator('[data-source-symbol="'+expected.handler+'#beforeAggregateSave"]').waitFor({state:'visible'});
  return {...metrics,forms:true,registrationCode:true,defaultHook:true,technicalRoundTrip:true};
}
