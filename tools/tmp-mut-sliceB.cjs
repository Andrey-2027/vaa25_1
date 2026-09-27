/*
 * Не-вакуумность среза B (D3.6.4): каждая мутация обязана быть поймана ровно своей проверкой.
 * Пять мутаций: три в production-шве reconcile-паритета и обе стороны книги надмножества.
 * Файлы восстанавливаются байт-в-байт (md5 до и после), дерево не остаётся мутированным.
 */
const fs = require('fs');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const ROOT = 'C:/JavaProject/TestVaadin25/GitVaa25';
const MVN = 'C:\\Program Files\\JetBrains\\IntelliJ IDEA Community Edition 2025.2.3\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd';
const JAVA_HOME = 'C:\\Program Files\\JetBrains\\IntelliJ IDEA Community Edition 2025.2.3\\jbr';

const VIEW = 'src/main/java/org/ip/views/reportstudio/ReportEditorView.java';
const PANEL = 'src/main/java/org/ip/views/reportstudio/ReportUserLayoutEditor.java';
const VARIANT = 'src/main/java/org/ip/views/reportstudio/structured/ReportEditorViewStructured.java';

const MUTATIONS = [
  {
    name: 'M1: убрать синхронизацию предупреждения после смены схемы',
    file: VIEW,
    find: '        syncOrphanedChangesNotice();\n        ReconcileResult reconcile = structureEditor.lastReconcile();',
    replace: '        ReconcileResult reconcile = structureEditor.lastReconcile();',
    expect: ['ReportEditorViewReconcileTest.missingColumnShowsNoticeAndOffersReplacement',
             'ReportEditorViewReconcileTest.noticeFollowsTheActualStateOfTheLists'],
  },
  {
    name: 'M2: не перечитывать списки после чистки модели',
    file: VIEW,
    find: '        userLayoutEditor.refresh();\n        syncOrphanedChangesNotice();\n    }\n\n    /**\n     * «Заменить поле»',
    replace: '        syncOrphanedChangesNotice();\n    }\n\n    /**\n     * «Заменить поле»',
    expect: ['ReportEditorViewReconcileTest.removalClearsNoticeAndRefreshesTheLists'],
  },
  {
    name: 'M3: вернуть диалог без возможности замены поля',
    file: VIEW,
    find: '        return new ReconcileDialog(reconcile, () -> applyReconcileRemoval(reconcile),\n                structureEditor.schemaFields(), this::applyReconcileReplacement);',
    replace: '        return new ReconcileDialog(reconcile, () -> applyReconcileRemoval(reconcile));',
    expect: ['ReportEditorViewReconcileTest.missingColumnShowsNoticeAndOffersReplacement'],
  },
  {
    name: 'M4: снять раздел «Оформление колонок» с канонического простого режима',
    file: PANEL,
    find: '        add(title, intro, fieldsSection(), groupsSection(), filtersSection(),\n                sortingSection(), appearanceSection(), pageHintSection(), expertNote());',
    replace: '        add(title, intro, fieldsSection(), groupsSection(), filtersSection(),\n                sortingSection(), pageHintSection(), expertNote());',
    expect: ['ReportEditorViewSupersetTest.canonicalViewOffersEveryCapabilityOfTheVariant'],
  },
  {
    name: 'M5: переименовать возможность в source-стеке (строка книги должна стать мёртвой)',
    file: VARIANT,
    find: '        Button addTotal = new Button("Добавить общий итог", event -> addUserTotal());',
    replace: '        Button addTotal = new Button("Добавить общий итог (старое)", event -> addUserTotal());',
    expect: ['ReportEditorViewSupersetTest.canonicalViewOffersEveryCapabilityOfTheVariant'],
  },
];

function md5(path) {
  return crypto.createHash('md5').update(fs.readFileSync(path)).digest('hex');
}

function failingTests() {
  const names = [];
  const reports = [
    'target/surefire-reports/org.ip.views.reportstudio.ReportEditorViewReconcileTest.txt',
    'target/surefire-reports/org.ip.views.reportstudio.ReportEditorViewSupersetTest.txt',
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
  const before = fs.readFileSync(path, 'utf8');
  const beforeHash = md5(path);
  if (!before.includes(mutation.find)) {
    console.log(`!! ${mutation.name}: якорь не найден`);
    failures++;
    continue;
  }
  fs.writeFileSync(path, before.replace(mutation.find, mutation.replace));
  if (md5(path) === beforeHash) {
    console.log(`!! ${mutation.name}: замена ничего не изменила`);
    failures++;
    continue;
  }
  let caught = [];
  try {
    spawnSync(`"${MVN}" -o -q test -DfailIfNoSpecifiedTests=false ` +
      `-Dtest=ReportEditorViewReconcileTest,ReportEditorViewSupersetTest`,
      { cwd: ROOT, env: { ...process.env, JAVA_HOME }, shell: true, encoding: 'utf8', maxBuffer: 1 << 26 });
    caught = failingTests();
  } finally {
    fs.writeFileSync(path, before);
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
