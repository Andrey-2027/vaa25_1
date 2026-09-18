#!/usr/bin/env node
/*
 * Анализ ссылок на типы платформы из приложения и из платформенных модулей.
 *
 * Зачем отдельный инструмент: гейт `PlatformPublicSurfaceTest` измерял приложение по строкам
 * `import`, поэтому fully-qualified и wildcard-ссылки обходили реестр (разбор D1/D2 нашёл так
 * пять пропущенных типов). Здесь та же методика доведена до полноты — разрешаются все три
 * синтаксиса, — и по ней строится reviewed-реестр ролей.
 *
 * Запуск: node scripts/analyze-platform-surface.mjs [--json]
 */
import fs from 'node:fs';
import path from 'node:path';

// Корень проекта берётся из рабочего каталога: скрипт запускается из корня репозитория
// (`node scripts/analyze-platform-surface.mjs`), а определение по import.meta.url на Windows
// в git-bash даёт неверный путь.
const PROJECT = process.cwd();
if (!fs.existsSync(path.join(PROJECT, 'scripts/local-dependencies.json'))) {
  console.error('Запустите из корня репозитория: node scripts/analyze-platform-surface.mjs');
  process.exit(2);
}

const ROOTS = {
  'app-main': 'src/main/java',
  'app-test': 'src/test/java',
  'platform-core': 'platform-core/src/main/java',
  'platform-tree': 'src/main/java/org/ipro',
  'leaf-crud-api': '../crudui/platform-crud-api/src/main/java',
  'leaf-identity-api': '../crudui/platform-identity-api/src/main/java',
};

function javaFiles(root, filter) {
  const absolute = path.resolve(PROJECT, root);
  if (!fs.existsSync(absolute)) return [];
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.java')) {
        const relative = path.relative(absolute, full).split(path.sep).join('/');
        if (!filter || filter(relative)) out.push({ file: relative, full });
      }
    }
  };
  walk(absolute);
  return out.sort((a, b) => a.file.localeCompare(b.file));
}

/** Каталоги платформенных модулей: все `platform-*` в чекауте плюс leaf-контракты соседнего crudui. */
function platformRoots() {
  const roots = fs.readdirSync(PROJECT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('platform-'))
    .map((entry) => `${entry.name}/src/main/java`)
    .filter((root) => fs.existsSync(path.resolve(PROJECT, root)));
  for (const leaf of ['platform-crud-api', 'platform-identity-api']) {
    const root = `../crudui/${leaf}/src/main/java`;
    if (fs.existsSync(path.resolve(PROJECT, root))) roots.push(root);
  }
  return roots.sort();
}

/**
 * Пространство имён считается не по этому чекауту, а по всему воркспейсу: часть платформенных
 * типов приходит из соседних проектов манифеста (`FilterGrid` и другие), и без них ссылка
 * выглядит неразрешимой. Ошибка здесь стоит дорого: именно на таком пропуске строился вывод
 * «пять production-типов не попадают в реестр».
 */
function siblingSourceRoots() {
  const manifestPath = path.join(PROJECT, 'scripts/local-dependencies.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const roots = [];
  for (const project of manifest.projects) {
    const relative = String(project.relativePath ?? '');
    if (!relative.startsWith('..')) continue;
    const root = `${relative}/src/main/java`;
    if (fs.existsSync(path.resolve(PROJECT, root))) roots.push(root);
  }
  return roots.sort();
}

/**
 * FQN по абсолютному пути: берётся часть после `src/main/java` (или `src/test/java`).
 * Выводить FQN относительно корня обхода нельзя: для платформенного кода, оставшегося в дереве
 * приложения, корень — это `.../org/ipro`, и тип получил бы имя `form.Dirtyable`.
 */
function fqnOf(fullPath) {
  const normalized = fullPath.split(path.sep).join('/');
  const marker = normalized.lastIndexOf('/src/main/java/') >= 0
    ? '/src/main/java/'
    : '/src/test/java/';
  const index = normalized.lastIndexOf(marker);
  const tail = index >= 0 ? normalized.slice(index + marker.length) : path.basename(normalized);
  return tail.replace(/\.java$/, '').split('/').join('.');
}

/** Все FQN типов платформы: по путям исходников, включая вложенные public-типы строкой token(). */
function universe() {
  const types = new Map(); // fqn -> root
  const roots = [
    ...platformRoots().map((root) => [root, root]),
    ...siblingSourceRoots().map((root) => [root, root]),
    [ROOTS['platform-tree'], 'platform-tree'],
  ];
  for (const [root, label] of roots) {
    const absolute = path.resolve(PROJECT, root);
    if (!fs.existsSync(absolute)) continue;
    for (const { full } of javaFiles(root)) {
      const fqn = fqnOf(full);
      types.set(fqn, label);
      const text = strip(fs.readFileSync(full, 'utf8'));
      for (const nested of nestedTypeNames(text)) types.set(`${fqn}.${nested}`, label);
    }
  }
  return types;
}

/** Имена объявленных в файле вложенных типов (top-level и второго уровня). */
function nestedTypeNames(text) {
  const names = new Set();
  const pattern = /\b(?:public|protected|private|static|final|abstract|sealed|non-sealed|\s)+\s*(?:class|interface|enum|record)\s+([A-Z][A-Za-z0-9_]*)/g;
  let match;
  while ((match = pattern.exec(text))) names.add(match[1]);
  return names;
}

/** Убирает комментарии и строковые литералы: ссылка в комментарии — не ссылка. */
function strip(text) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    const two = text.slice(i, i + 2);
    if (two === '//') {
      while (i < text.length && text[i] !== '\n') i++;
    } else if (two === '/*') {
      i += 2;
      while (i < text.length && text.slice(i, i + 2) !== '*/') i++;
      i += 2;
    } else if (text[i] === '"') {
      i++;
      while (i < text.length && text[i] !== '"') i += text[i] === '\\' ? 2 : 1;
      i++;
    } else if (text[i] === "'") {
      i++;
      while (i < text.length && text[i] !== "'") i += text[i] === '\\' ? 2 : 1;
      i++;
    } else {
      out += text[i];
      i++;
    }
  }
  return out;
}

// Диагностика парсера: `--strip <файл>` печатает исходник после удаления комментариев и строк.
// Нужна потому, что «тип не найден» может означать как отсутствие ссылки, так и дефект
// разбора (апостроф в комментарии притворяется началом char-литерала и съедает код).
const stripIndex = process.argv.indexOf('--strip');
if (stripIndex > 0 && process.argv[stripIndex + 1]) {
  console.log(strip(fs.readFileSync(process.argv[stripIndex + 1], 'utf8')));
  process.exit(0);
}

const UNIVERSE = universe();
const KNOWN = [...UNIVERSE.keys()].sort((a, b) => b.length - a.length);

function resolve(token) {
  for (const candidate of KNOWN) {
    if (token === candidate || token.startsWith(candidate + '.')) return candidate;
  }
  return null;
}

/** Ссылки на типы платформы в дереве исходников: imports + wildcard + FQ. */
function references(root, filter) {
  const found = new Map(); // fqn -> Set<file>
  const unresolved = new Map();
  const add = (fqn, file) => {
    if (!found.has(fqn)) found.set(fqn, new Set());
    found.get(fqn).add(file);
  };
  for (const { file, full } of javaFiles(root, filter)) {
    const text = fs.readFileSync(full, 'utf8');
    const code = strip(text);
    const imports = /^import\s+(?:static\s+)?(org\.ipro\.[A-Za-z0-9_.]+(?:\.\*)?)\s*;/gm;
    const wildcardPackages = [];
    let match;
    while ((match = imports.exec(code))) {
      const target = match[1];
      if (target.endsWith('.*')) wildcardPackages.push(target.slice(0, -2));
      else add(target, file);
    }
    const codeWithoutImports = code.split('\n').filter((line) => !/^\s*import\s/.test(line)).join('\n');
    for (const raw of new Set(codeWithoutImports.match(/(?<![\w.])org\.ipro\.[A-Za-z0-9_.]+/g) ?? [])) {
      const token = raw.replace(/\.$/, '');
      const resolved = resolve(token);
      if (resolved) {
        add(resolved, file);
      } else if (!KNOWN.some((known) => known.startsWith(token + '.'))) {
        // Порядок важен: сначала разрешение, потом отсев package-префиксов. Обратный порядок
        // отбрасывал бы сам тип, у которого есть вложенные типы (org.ipro.jr.run.JpqlDatasetRunner
        // перестал бы считаться ссылкой только потому, что у него есть вложенный record).
        unresolved.set(token, (unresolved.get(token) ?? new Set([file])));
      }
    }
    // wildcard import: любое простое имя, совпадающее с типом пакета, — это ссылка.
    for (const pkg of wildcardPackages) {
      for (const [fqn] of UNIVERSE) {
        if (!fqn.startsWith(pkg + '.')) continue;
        const simple = fqn.slice(pkg.length + 1);
        if (simple.includes('.')) continue;
        if (new RegExp(`\\b${simple}\\b`).test(code)) add(fqn, file);
      }
      add(`import ${pkg}.*`, file); // метка самого wildcard-импорта: он тоже нарушение
    }
  }
  return { found, unresolved };
}

function describe(label, { found, unresolved }) {
  console.log(`\n## ${label}: ${found.size} типов`);
  for (const [fqn, files] of [...found].sort()) console.log(`${fqn}\t${files.size}`);
  if (unresolved.size) {
    console.log(`  НЕРАЗРЕШЁННЫЕ ссылки org.ipro.*: ${[...unresolved.keys()].sort().join(', ')}`);
  }
}

const appMain = references(ROOTS['app-main'], (f) => f.startsWith('org/ip/'));
const appTest = references(ROOTS['app-test'], (f) => f.startsWith('org/ip/'));
const coreTypes = javaFiles(ROOTS['platform-core']).map(({ full }) => fqnOf(full));

/**
 * Ссылки на platform-core из остального платформенного production-кода: база MODULE_API.
 * Кроме соседних модулей сюда входит платформенный код, ещё лежащий в дереве приложения
 * (`form`, `reportstudio`, `telemetry`, `vaadin`): именно он станет содержимым будущих
 * platform-vaadin / platform-report-* / autoconfigure, и его контракт с core тоже нужно знать.
 */
const MODULE_TYPES = new Set(coreTypes);
const moduleReferences = new Map();
for (const root of [...platformRoots(), ROOTS['platform-tree']]) {
  if (root === ROOTS['platform-core']) continue;
  const { found } = references(root);
  for (const [fqn, files] of found) {
    if (!MODULE_TYPES.has(fqn)) continue;
    if (!moduleReferences.has(fqn)) moduleReferences.set(fqn, new Set());
    for (const file of files) moduleReferences.get(fqn).add(`${root}:${file}`);
  }
}
const MODULE_NAMED = new Set(moduleReferences.keys());

/**
 * Заморозка fully-qualified ссылок: файл → сколько раз он называет тип платформы без импорта.
 * Нужна потому, что запретить FQ одним движением нельзя (сейчас это тысячи употреблений), а
 * разрешить их рост — значит оставить дверь, через которую реестр обходили.
 */
if (process.argv.includes('--fq-files')) {
  const lines = [];
  let total = 0;
  for (const { file, full } of javaFiles(ROOTS['app-main'], (name) => name.startsWith('org/ip/'))) {
    const code = strip(fs.readFileSync(full, 'utf8'))
      .split('\n').filter((line) => !/^\s*import\s/.test(line)).join('\n');
    let count = 0;
    for (const raw of new Set(code.match(/(?<![\w.])org\.ipro\.[A-Za-z0-9_.]+/g) ?? [])) {
      const token = raw.replace(/\.$/, '');
      if (resolve(token)) count++;
    }
    if (count > 0) {
      lines.push(`src/main/java/${file} ${count}`);
      total += count;
    }
  }
  console.log(`# файлов: ${lines.length}, ссылок: ${total}`);
  console.log(lines.sort().join('\n'));
} else if (process.argv.includes('--core-registry')) {
/**
 * Черновик семантического реестра platform-core: одна базовая роль на каждый production-тип
 * плюс ортогональный test-usage overlay. Правила и явные решения — те же, что записаны в шапке
 * `platform-core-surface.txt`; скрипт воспроизводит механическую часть, решения принимаются
 * ревью и живут в файле реестра.
 */
  const d1 = new Map();
  for (const line of fs.readFileSync(
      path.join(PROJECT, 'src/test/resources/platform-public-surface.txt'), 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const parts = trimmed.split(/\s+/);
    d1.set(parts[1], parts[0]);
  }
  const d1Role = (fqn) => {
    for (const [key, role] of d1) if (key === fqn || key.startsWith(fqn + '.')) return role;
    return null;
  };
  // Явные решения ревью, которые механический срез не выводит.
  const overrides = {
    'org.ipro.crud.NaturalKeyCreateSupport': 'APP_API',
    'org.ipro.data.EntityDataAccess': 'APP_API',
    'org.ipro.crud.LookupService': 'MODULE_API',
    'org.ipro.crud.ServiceLocator': 'MODULE_API',
    'org.ipro.search.JpaGlobalSearchProvider': 'INTERNAL',
  };
  const moduleNamed = new Set(moduleReferences.keys());
  const roles = new Map();
  for (const fqn of coreTypes) {
    if (overrides[fqn]) roles.set(fqn, overrides[fqn]);
    else if (d1Role(fqn) === 'API') roles.set(fqn, 'APP_API');
    else if (d1Role(fqn) === 'SPI') roles.set(fqn, 'APP_SPI');
    else if (d1Role(fqn) === 'legacy-internal') roles.set(fqn, 'MODULE_API');
    else roles.set(fqn, moduleNamed.has(fqn) ? 'MODULE_API' : 'INTERNAL');
  }
  const lines = [];
  for (const role of ['APP_API', 'APP_SPI', 'MODULE_API', 'INTERNAL']) {
    const members = [...roles].filter(([, value]) => value === role).map(([fqn]) => fqn).sort();
    lines.push(`# ---- ${role} (${members.length})`);
    for (const fqn of members) lines.push(`${role} ${fqn}`);
  }
  // test-usage overlay: типы, которые называют только тесты приложения. Заморозка — по
  // простому имени, тем же критерием, что у legacy-записей D1-реестра.
  // Комментарии и строковые литералы снимаются: упоминание в комментарии — не ссылка. Без этого
  // реестр получал записи, которые гейт (он считает по коду) тут же признавал лишними.
  const testSources = javaFiles(ROOTS['app-test'], (file) => file.startsWith('org/ip/'))
    .map(({ file, full }) => ({ file, code: strip(fs.readFileSync(full, 'utf8')) }));
  const mainNamed = (fqn) => [...appMain.found.keys()]
    .some((name) => name === fqn || name.startsWith(fqn + '.'));
  const owners = new Set();
  for (const name of appTest.found.keys()) {
    const owner = coreTypes.filter((fqn) => name === fqn || name.startsWith(fqn + '.'))
      .sort((a, b) => b.length - a.length)[0];
    if (owner && !mainNamed(owner)) owners.add(owner);
  }
  lines.push('', `# ---- test-usage overlay (${owners.size})`);
  for (const owner of [...owners].sort()) {
    const simple = owner.slice(owner.lastIndexOf('.') + 1);
    const reference = new RegExp(`\\b${simple}\\b`);
    // Пути — относительно `src/test/java`, как в D1-реестре (там они относительно
    // `src/main/java`): один и тот же способ адресации файла в обеих заморозках.
    const hits = testSources.filter(({ code }) => reference.test(code))
      .map(({ file }) => file).sort();
    lines.push(`test-usage ${owner} ${hits.join(' ')}`);
  }
  console.log(lines.join('\n'));
} else if (process.argv.includes('--vaadin-registry')) {
/**
 * Черновик семантического реестра будущего `platform-vaadin` (D3.5.0): production-типы
 * `org.ipro.form`, `org.ipro.vaadin` и Vaadin-адаптера телеметрии, по одной базовой роли на тип.
 * Правила и явные решения записаны в шапке `platform-vaadin-surface.txt`; скрипт воспроизводит
 * механическую часть, а решения принимаются ревью — иначе проверялось бы то же правило, которым
 * список построен.
 */
  const VAADIN_ROOTS = [
    'src/main/java/org/ipro/form',
    'src/main/java/org/ipro/vaadin',
    'src/main/java/org/ip/telemetry/vaadin',
  ];
  const vaadinTypes = VAADIN_ROOTS
    .flatMap((root) => javaFiles(root).map(({ full }) => fqnOf(full)))
    .sort();

  const d1 = new Map();
  for (const line of fs.readFileSync(
      path.join(PROJECT, 'src/test/resources/platform-public-surface.txt'), 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const parts = trimmed.split(/\s+/);
    d1.set(parts[1], parts[0]);
  }
  const d1Role = (fqn) => {
    for (const [key, role] of d1) if (key === fqn || key.startsWith(fqn + '.')) return role;
    return null;
  };

  // Явные решения ревью: механический срез их не выводит. Каждый legacy-internal тип обязан
  // получить решение здесь, а не унаследовать его из правила.
  const overrides = {
    // Plan D3.5: координатор не замораживается как публичный API — он остаётся реализацией.
    'org.ipro.form.coordinator.FormCoordinator': 'INTERNAL',
  };

  const moduleNamed = new Set(moduleReferences.keys());
  const roles = new Map();
  for (const fqn of vaadinTypes) {
    if (overrides[fqn]) roles.set(fqn, overrides[fqn]);
    else if (d1Role(fqn) === 'API') roles.set(fqn, 'APP_API');
    else if (d1Role(fqn) === 'SPI') roles.set(fqn, 'APP_SPI');
    else if (d1Role(fqn) === 'legacy-internal') roles.set(fqn, 'APP_API_DRAFT');
    else roles.set(fqn, moduleNamed.has(fqn) ? 'MODULE_API' : 'INTERNAL');
  }

  const lines = [];
  for (const role of ['APP_API', 'APP_SPI', 'MODULE_API', 'INTERNAL', 'APP_API_DRAFT']) {
    const members = [...roles].filter(([, value]) => value === role).map(([fqn]) => fqn).sort();
    lines.push(`# ---- ${role} (${members.length})`);
    for (const fqn of members) lines.push(`${role} ${fqn}`);
  }
  console.log(lines.join('\n'));
  console.log(`\n# всего типов: ${vaadinTypes.length}`);
  console.log('# приложение (src/main, org/ip) называет:');
  for (const fqn of vaadinTypes.filter((name) => appMain.found.has(name))) {
    console.log(`  main ${fqn} (${d1Role(fqn) ?? 'нет в D1-реестре'})`);
  }
  console.log('# ни приложение, ни тесты не называют (кандидаты в INTERNAL):');
  for (const fqn of vaadinTypes
      .filter((name) => !appMain.found.has(name) && !appTest.found.has(name))) {
    console.log(`  INTERNAL ${fqn}`);
  }
} else if (process.argv.includes('--delta')) {
  // Дельта к reviewed-реестру: чего в реестре нет, что в реестре лишнее. Именно этот вывод
  // превращает найденную дыру (приложение ссылается на тип, которого нет в реестре) в список
  // конкретных решений.
  const registry = new Map();
  const registryPath = path.join(PROJECT, 'src/test/resources/platform-public-surface.txt');
  for (const line of fs.readFileSync(registryPath, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const parts = trimmed.split(/\s+/);
    registry.set(parts[1], parts[0]);
  }
  const namedMain = [...appMain.found.keys()];
  const namedTest = [...appTest.found.keys()];
  const named = new Set([...namedMain, ...namedTest]);
  const missing = [...named].filter((fqn) => !registry.has(fqn)).sort();
  const stale = [...registry.keys()].filter((fqn) => !named.has(fqn)).sort();
  console.log(`## есть в коде, нет в реестре: ${missing.length}`);
  for (const fqn of missing) {
    console.log(`${fqn}\t${namedMain.includes(fqn) ? 'main' : ''}${namedTest.includes(fqn) ? '+test' : ''}`);
  }
  console.log(`\n## есть в реестре, но код больше не называет: ${stale.length}`);
  for (const fqn of stale) console.log(`${registry.get(fqn)}\t${fqn}`);
  const legacy = [...registry].filter(([, role]) => role === 'legacy-internal').map(([fqn]) => fqn);
  console.log(`\n## всего в реестре: ${registry.size}; legacy-internal: ${legacy.length}`);
} else if (process.argv.includes('--json')) {
  const payload = {
    coreTypes,
    appMain: Object.fromEntries([...appMain.found].sort().map(([k, v]) => [k, [...v].sort()])),
    appTest: Object.fromEntries([...appTest.found].sort().map(([k, v]) => [k, [...v].sort()])),
    moduleReferences: Object.fromEntries([...moduleReferences].sort()
      .map(([k, v]) => [k, [...v].sort()])),
  };
  console.log(JSON.stringify(payload, null, 2));
} else {
  console.log(`# Типов platform-core: ${coreTypes.length}`);
  describe('app-main ссылается на', appMain);
  describe('app-test ссылается на', appTest);
  const named = new Set([...appMain.found.keys(), ...appTest.found.keys()]);
  const coreNamed = coreTypes.filter((fqn) => named.has(fqn));
  console.log(`\n## platform-core, названные приложением: ${coreNamed.length}/${coreTypes.length}`);
  for (const fqn of coreNamed.sort()) {
    const inMain = appMain.found.has(fqn) ? 'main' : '';
    const inTest = appTest.found.has(fqn) ? 'test' : '';
    console.log(`${fqn}\t${[inMain, inTest].filter(Boolean).join('+')}`);
  }
  const untouched = coreTypes.filter((fqn) => !named.has(fqn));
  console.log(`\n## platform-core, НЕ названные приложением: ${untouched.length}`);
  for (const fqn of untouched.sort()) console.log(fqn);
  console.log(`\n## platform-core, используемые другими платформенными модулями: ${MODULE_NAMED.size}`);
  for (const fqn of [...MODULE_NAMED].sort()) console.log(`${fqn}\t${moduleReferences.get(fqn).size}`);
  if (appMain.unresolved.size || appTest.unresolved.size) {
    console.log('\n## ВНИМАНИЕ: неразрешённые ссылки');
    console.log([...appMain.unresolved.keys(), ...appTest.unresolved.keys()].join('\n'));
  }
}
