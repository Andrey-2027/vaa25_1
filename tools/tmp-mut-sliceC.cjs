/*
 * Не-вакуумность среза C (D3.6.5): каждая мутация обязана упасть ровно своей проверкой.
 * Шесть мутаций: три в теме/классах (забор стилей) и три в панели простого режима.
 * Файлы восстанавливаются байт-в-байт (md5 до и после), дерево не остаётся мутированным.
 */
const fs = require('fs');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const ROOT = 'C:/JavaProject/TestVaadin25/GitVaa25';
const MVN = 'C:\\Program Files\\JetBrains\\IntelliJ IDEA Community Edition 2025.2.3\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd';
const JAVA_HOME = 'C:\\Program Files\\JetBrains\\IntelliJ IDEA Community Edition 2025.2.3\\jbr';

const CSS = 'src/main/frontend/themes/default/styles.css';
const VIEW = 'src/main/java/org/ip/views/reportstudio/ReportEditorView.java';
const PANEL = 'src/main/java/org/ip/views/reportstudio/ReportUserLayoutEditor.java';

const MUTATIONS = [
  {
    name: 'M1: правило контролов без scope (глобальная окраска форм)',
    file: CSS,
    find: '.report-editor vaadin-text-area,\n.report-editor vaadin-text-field,',
    replace: 'vaadin-text-area,\n.report-editor vaadin-text-field,',
    expect: ['ReportEditorStyleScopeTest.formControlRulesAlwaysHaveAnOwner'],
    crlf: true,
  },
  {
    name: 'M2: вернуть мёртвое правило pill-toggle',
    file: CSS,
    find: '.report-editor vaadin-details summary {',
    replace: '.report-editor .pill-toggle {\n  border-radius: 12px;\n}\n.report-editor vaadin-details summary {',
    expect: ['ReportEditorStyleScopeTest.variantScopesAndDeadRulesAreGone'],
    crlf: true,
  },
  {
    name: 'M3: экран вешает посторонний класс (книга классов обеднеет)',
    file: VIEW,
    find: '        addClassName("report-editor");',
    replace: '        addClassName("report-editor-ghost");',
    expect: ['ReportEditorStyleScopeTest.productionAddsOnlyTheLedgerClasses'],
  },
  {
    name: 'M4: правка поля добавляется в модель без уведомления владельца',
    file: PANEL,
    find: '            context.notify(error.getMessage());\n            return;\n        }\n        context.modelChanged();\n    }\n\n    private void addUserGroup() {',
    replace: '            context.notify(error.getMessage());\n            return;\n        }\n        // МУТАЦИЯ: уведомление потеряно\n    }\n\n    private void addUserGroup() {',
    expect: ['ReportUserLayoutEditorTest.addFieldButtonAddsTheColumnAndNotifiesOnce'],
  },
  {
    name: 'M5: отклонённый повтор поля делает отчёт «изменённым»',
    file: PANEL,
    find: '            context.notify(error.getMessage());\n            return;\n        }\n        context.modelChanged();\n    }\n\n    private void addUserGroup() {',
    replace: '            context.notify(error.getMessage());\n        }\n        context.modelChanged();\n    }\n\n    private void addUserGroup() {',
    expect: ['ReportUserLayoutEditorTest.duplicateFieldIsRejectedWithoutTouchingTheModel'],
  },
  {
    name: 'M6: невалидный отбор всё же записывается в модель',
    file: PANEL,
    find: '        if (!userFilterEditor.validationErrors().isEmpty()) {\n            return;\n        }',
    replace: '        if (false) {\n            return;\n        }',
    expect: ['ReportUserLayoutEditorTest.invalidConditionIsNotWrittenToTheModel'],
  },
];

function md5(path) {
  return crypto.createHash('md5').update(fs.readFileSync(path)).digest('hex');
}

function mutationReportsExist() {
  return ['org.ip.views.reportstudio.ReportEditorStyleScopeTest',
          'org.ip.views.reportstudio.ReportUserLayoutEditorTest']
    .some(name => fs.existsSync(`${ROOT}/target/surefire-reports/${name}.txt`));
}

function failingTests() {
  const names = [];
  const reports = [
    'target/surefire-reports/org.ip.views.reportstudio.ReportEditorStyleScopeTest.txt',
    'target/surefire-reports/org.ip.views.reportstudio.ReportUserLayoutEditorTest.txt',
  ];
  for (const report of reports) {
    const full = `${ROOT}/${report}`;
    if (!fs.existsSync(full)) continue;
    for (const line of fs.readFileSync(full, 'utf8').split(/\r?\n/)) {
      if ((line.includes('<<< FAILURE!') || line.includes('<<< ERROR!'))
          && !line.startsWith('Tests run:')) {
        names.push(line.split(' -- ')[0].trim());
      }
    }
  }
  return names;
}

let failures = 0;
for (const mutation of MUTATIONS) {
  const path = `${ROOT}/${mutation.file}`;
  const original = fs.readFileSync(path, 'utf8');
  const beforeHash = md5(path);
  const probe = mutation.crlf ? original.replace(/\r\n/g, '\n') : original;
  if (!probe.includes(mutation.find)) {
    console.log(`!! ${mutation.name}: якорь не найден`);
    failures++;
    continue;
  }
  const mutated = (mutation.crlf ? probe : original).replace(mutation.find, mutation.replace);
  fs.writeFileSync(path, mutated);
  if (md5(path) === beforeHash) {
    console.log(`!! ${mutation.name}: замена ничего не изменила`);
    failures++;
    continue;
  }
  let caught = [];
  try {
    // Stale-отчёты подделывают результат: если maven не дошёл до тестов,
    // отчётов не будет вовсе — и это обязана быть ошибка, а не «мутация не поймана».
    for (const report of [
      'target/surefire-reports/org.ip.views.reportstudio.ReportEditorStyleScopeTest.txt',
      'target/surefire-reports/org.ip.views.reportstudio.ReportUserLayoutEditorTest.txt',
    ]) {
      try { fs.rmSync(`${ROOT}/${report}`); } catch (_) { /* нет файла — не страшно */ }
    }
    spawnSync(`"${MVN}" -o -q test -DfailIfNoSpecifiedTests=false ` +
      `-Dtest=ReportEditorStyleScopeTest,ReportUserLayoutEditorTest`,
      { cwd: ROOT, env: { ...process.env, JAVA_HOME }, shell: true, encoding: 'utf8', maxBuffer: 1 << 26 });
    caught = failingTests();
    if (caught.length === 0 && !mutationReportsExist()) {
      console.log('      !! прогон не дошёл до тестов (отчётов нет)');
      caught = ['__NO_REPORTS__'];
    }
  } finally {
    fs.writeFileSync(path, original);
  }
  const restored = md5(path) === beforeHash;
  const missing = mutation.expect.filter(name => !caught.some(got => got.endsWith(name)));
  const ok = restored && missing.length === 0;
  if (!ok) failures++;
  console.log(`${ok ? 'OK  ' : 'BAD '} ${mutation.name}`);
  console.log(`      ожидали: ${mutation.expect.join(', ')}`);
  console.log(`      поймали: ${caught.length ? caught.join(', ') : '—'}`);
  if (!restored) console.log('      !! файл не восстановлен побайтно');
}
console.log(failures === 0 ? 'MUTATIONS_ALL_CAUGHT' : `MUTATIONS_PROBLEMS=${failures}`);
