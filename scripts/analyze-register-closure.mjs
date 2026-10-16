#!/usr/bin/env node
/*
 * Замер замыкания пакета `org.ipro.register` на приложение: что именно движок регистра тянет
 * из прикладного кода и из платформенных артефактов.
 *
 * Зачем отдельный инструмент, а не grep по импортам. Замер D2 → D3 показал, что разбор по
 * строкам `import` занижает замыкание в разы: ссылка в теле метода полным именем, wildcard-импорт
 * пакета и однопачечная зависимость посещённого типа в импортах не видны, а именно на таком
 * пропуске стояли выводы вида «подсистема зависит на один тип». Здесь разбор идёт до полноты —
 * четыре синтаксиса кода плюс отдельно строковые/конфигурационные ссылки, которые в коде не видны,
 * но в рантайме связывают:
 *
 *   1) `import` и `static import` (в том числе wildcard — как ссылка на пакет и на каждый тип,
 *      простое имя которого реально используется в файле);
 *   2) полное имя в теле кода (`org.ipro.X.Y`, `org.ip.X.Y`) — из кода без комментариев и
 *      строковых литералов: упоминание в комментарии ссылкой не считается;
 *   3) простое имя типа того же пакета или пакета wildcard-импорта;
 *   4) `package`-объявления не считаются ссылками вовсе.
 *
 * Отдельно измеряются ссылки, которых нет в коде: строковые литералы с FQN (`"org.ip.model.X"`,
 * имена классов для рефлексии, JPQL с типами) и файлы регистрации (`META-INF/spring/*.imports`,
 * `spring.factories`, XML). Это разные вещи: первое — связь исходников, второе — связь сборки и
 * рантайма. Замер D3 знает цену их смешения: зависимость, которой не было в исходниках,
 * нашлась именно на честной сборке.
 *
 * Владельцы типов размечаются по источнику: `application` (`org/ip` в дереве приложения),
 * `register` (seed), `platform-in-tree:<пакет>` (платформенный код, ещё лежащий в дереве),
 * `platform-<модуль>` и соседние проекты манифеста (`../crudui/*`, `../FilterGrid/*`).
 * Неразрешённые ссылки печатаются явно: тип без исходников в воркспейсе — это опубликованный
 * артефакт, и выдавать его за «нет зависимости» нельзя.
 *
 * Запуск из корня репозитория:
 *   node scripts/analyze-register-closure.mjs [--json] [--seeds main|test|all]
 */
import fs from 'node:fs';
import path from 'node:path';

const PROJECT = process.cwd();
if (!fs.existsSync(path.join(PROJECT, 'scripts/local-dependencies.json'))) {
  console.error('Запустите из корня репозитория: node scripts/analyze-register-closure.mjs');
  process.exit(2);
}

// === корни источников ===

/** Каталоги платформенных модулей чекаута: каждый `platform-*` со своими исходниками. */
function platformRoots() {
  return fs.readdirSync(PROJECT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('platform-'))
    .map((entry) => ({ label: entry.name, dir: `${entry.name}/src/main/java` }))
    .filter(({ dir }) => fs.existsSync(path.resolve(PROJECT, dir)))
    .sort((a, b) => a.label.localeCompare(b.label));
}

/**
 * Соседние проекты манифеста: однопачечные (`../crudui/platform-crud-api`) и многомодульные
 * (`../FilterGrid/filtergrid-core`) — оба вида встречаются, поэтому после `src/main/java`
 * проверяется также один уровень вложенности.
 */
function siblingRoots() {
  const manifest = JSON.parse(fs.readFileSync(
    path.join(PROJECT, 'scripts/local-dependencies.json'), 'utf8'));
  const roots = [];
  for (const project of manifest.projects) {
    const relative = String(project.relativePath ?? '');
    if (!relative.startsWith('..')) continue;
    const direct = `${relative}/src/main/java`;
    const projectName = path.basename(relative);
    if (fs.existsSync(path.resolve(PROJECT, direct))) roots.push({ label: projectName, dir: direct });
    const absolute = path.resolve(PROJECT, relative);
    if (!fs.existsSync(absolute)) continue;
    for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const nested = `${relative}/${entry.name}/src/main/java`;
      if (fs.existsSync(path.resolve(PROJECT, nested))) {
        roots.push({ label: `${projectName}/${entry.name}`, dir: nested });
      }
    }
  }
  return roots.sort((a, b) => a.label.localeCompare(b.label));
}

const ROOTS = [
  { label: 'app-tree', dir: 'src/main/java' },
  { label: 'app-tree-test', dir: 'src/test/java' },
  ...platformRoots(),
  ...siblingRoots(),
];

/** Владелец типа по корню и пути внутри него. */
function ownerOf(label, relative) {
  const segments = relative.split('/');
  if (label === 'app-tree' || label === 'app-tree-test') {
    const test = label.endsWith('-test');
    if (segments[0] === 'org' && segments[1] === 'ip') return test ? 'application-test' : 'application';
    if (segments[0] === 'org' && segments[1] === 'ipro') {
      const group = segments[2] ?? '';
      if (group === 'register') return test ? 'register-test' : 'register';
      return `${test ? 'platform-in-tree-test' : 'platform-in-tree'}:org.ipro.${group}`;
    }
    return `${label}:${segments.slice(0, 2).join('.')}`;
  }
  return label;
}

// === разбор исходников ===

function javaFiles(dir) {
  const absolute = path.resolve(PROJECT, dir);
  if (!fs.existsSync(absolute)) return [];
  const out = [];
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.java')) {
        out.push(path.relative(absolute, full).split(path.sep).join('/'));
      }
    }
  };
  walk(absolute);
  return out.sort();
}

/** Убирает комментарии и строковые литералы: ссылка в комментарии — не ссылка. */
function strip(text) {
  let out = '';
  let index = 0;
  while (index < text.length) {
    const two = text.slice(index, index + 2);
    if (two === '//') {
      while (index < text.length && text[index] !== '\n') index++;
    } else if (two === '/*') {
      index += 2;
      while (index < text.length && text.slice(index, index + 2) !== '*/') index++;
      index += 2;
    } else if (text[index] === '"') {
      index++;
      while (index < text.length && text[index] !== '"') index += text[index] === '\\' ? 2 : 1;
      index++;
    } else if (text[index] === "'") {
      index++;
      while (index < text.length && text[index] !== "'") index += text[index] === '\\' ? 2 : 1;
      index++;
    } else {
      out += text[index];
      index++;
    }
  }
  return out;
}

/**
 * Комментарии и строковые литералы врозь. Смешивать их нельзя: `{@link org.ipro.X}` в javadoc
 * зависимостью не считается (правило D1), а имя класса в литерале — считается: по нему работает
 * рефлексия (`ManagedEntityCatalog` в `RecorderTypeRegistry`), даже когда в коде ссылки нет.
 */
function commentsAndLiterals(text) {
  const literals = [];
  const comments = [];
  let index = 0;
  while (index < text.length) {
    const two = text.slice(index, index + 2);
    if (two === '//') {
      const start = index;
      while (index < text.length && text[index] !== '\n') index++;
      comments.push(text.slice(start, index));
    } else if (two === '/*') {
      const start = index;
      index += 2;
      while (index < text.length && text.slice(index, index + 2) !== '*/') index++;
      index += 2;
      comments.push(text.slice(start, index));
    } else if (text[index] === '"') {
      const start = index;
      index++;
      while (index < text.length && text[index] !== '"') index += text[index] === '\\' ? 2 : 1;
      index++;
      literals.push(text.slice(start + 1, index - 1));
    } else if (text[index] === "'") {
      index++;
      while (index < text.length && text[index] !== "'") index += text[index] === '\\' ? 2 : 1;
      index++;
    } else {
      index++;
    }
  }
  return { literals, comments };
}

/** Объявленные в файле типы: top-level и вложенные — вложенные тоже адресуемы по FQN. */
function declaredTypes(relative, code) {
  const fqn = relative.replace(/\.java$/, '').split('/').join('.');
  const names = new Set([fqn]);
  const pattern = /\b(?:class|interface|enum|record)\s+([A-Z][A-Za-z0-9_]*)/g;
  let match;
  while ((match = pattern.exec(code))) names.add(`${fqn}.${match[1]}`);
  return [...names];
}

// Диагностика разбора: `--tokens <файл>` печатает, что найдено в комментариях и литералах.
// Нужна по той же причине, что `--strip` у analyze-platform-surface.mjs: «ноль ссылок» может
// значить и отсутствие ссылки, и дефект разбора.
if (process.argv.includes('--tokens') && process.argv[process.argv.indexOf('--tokens') + 1]) {
  const { literals, comments } = commentsAndLiterals(
    fs.readFileSync(process.argv[process.argv.indexOf('--tokens') + 1], 'utf8'));
  console.log(`comment blocks: ${comments.length}, literals: ${literals.length}`);
  console.log('literal tokens:', [...new Set(literals.join('\n').match(/[\w.$]*org\.(?:ip|ipro)[\w.$]*/g) ?? [])]);
  console.log('comment tokens:', [...new Set(comments.join('\n').match(/[\w.$]*org\.(?:ip|ipro)[\w.$]*/g) ?? [])]);
  process.exit(0);
}

/** Все файлы со своими типами и разобранным кодом. Один FQN может объявляться в нескольких корнях. */
function scan() {
  const files = [];
  const byFqn = new Map();
  for (const { label, dir } of ROOTS) {
    for (const relative of javaFiles(dir)) {
      const full = path.resolve(PROJECT, dir, relative);
      const raw = fs.readFileSync(full, 'utf8');
      const code = strip(raw);
      const { literals, comments } = commentsAndLiterals(raw);
      // Токен без типа (пакет целиком) отсеет resolve() при отчёте; здесь важно не потерять
      // имя вида org.ipro.crud.BaseEntity — тип не обязан идти сразу за пространством имён.
      const tokensIn = (source) => [...new Set(source.match(/[\w.$]*org\.(?:ip|ipro)[\w.$]*/g) ?? [])]
        .filter((token) => /^org\.(?:ip|ipro)\./.test(token));
      const record = {
        root: label,
        dir,
        relative,
        full,
        owner: ownerOf(label, relative),
        code,
        types: declaredTypes(relative, code),
        literals: tokensIn(literals.join('\n')),
        documented: [...new Set(comments.flatMap((comment) => [
          ...(comment.match(/\{@link(?:plain)?\s+([^}\s]+)/g) ?? [])
            .map((hit) => hit.replace(/^\{@link(?:plain)?\s+/, '')),
          ...(comment.match(/[\w.$]*org\.(?:ip|ipro)[\w.$]*/g) ?? []),
        ]))].filter((token) => /^org\.(?:ip|ipro)\./.test(token)),
      };
      files.push(record);
      for (const type of record.types) {
        if (!byFqn.has(type)) byFqn.set(type, []);
        byFqn.get(type).push(record);
      }
    }
  }
  return { files, byFqn };
}

const { files, byFqn } = scan();
const KNOWN = [...byFqn.keys()].sort((a, b) => b.length - a.length || a.localeCompare(b));

/** Длиннейшее совпадение по известным FQN: у типа с вложенными типами имя тоже должно разрешаться. */
function resolve(token) {
  for (const candidate of KNOWN) {
    if (token === candidate || token.startsWith(candidate + '.')) return candidate;
  }
  return null;
}

const packageTypes = new Map(); // пакет -> типы, объявленные прямо в нём (для простых имён)
for (const [fqn, records] of byFqn) {
  const cut = fqn.lastIndexOf('.');
  if (cut < 0) continue;
  const pkg = fqn.slice(0, cut);
  const simple = fqn.slice(cut + 1);
  if (simple.includes('.')) continue;
  if (!packageTypes.has(pkg)) packageTypes.set(pkg, new Map());
  packageTypes.get(pkg).set(simple, records);
}

/**
 * Ссылки одного файла: четыре синтаксиса кода (imports, полное имя, простое имя своего пакета,
 * простые имена пакетов wildcard-импорта) плюс неразрешённые токены.
 */
function referencesOf(file) {
  const found = new Map(); // fqn -> 'import' | 'fully-qualified' | 'simple' | 'wildcard'
  const unresolved = new Map();
  const add = (fqn, kind) => {
    if (!found.has(fqn)) found.set(fqn, new Set());
    found.get(fqn).add(kind);
  };
  const packageName = file.relative.replace(/\.java$/, '').split('/').join('.');
  const ownPackage = packageName.slice(0, packageName.lastIndexOf('.'));
  const code = file.code;
  const wildcardPackages = [];

  for (const match of code.matchAll(/^\s*import\s+(?:static\s+)?([\w.$]+)(\.\*)?\s*;/gm)) {
    const target = match[1];
    if (match[2]) wildcardPackages.push(target);
    else if (resolve(target)) add(resolve(target), 'import');
    else unresolved.set(target, (unresolved.get(target) ?? 0) + 1);
  }

  const withoutImports = code.split('\n').filter((line) => !/^\s*import\s/.test(line)).join('\n');
  for (const raw of new Set(withoutImports.match(/(?<![\w.])org\.(?:ip|ipro)\.[\w.$]+/g) ?? [])) {
    const token = raw.replace(/\.$/, '');
    const resolved = resolve(token);
    if (resolved) add(resolved, 'fully-qualified');
    else if (!KNOWN.some((known) => known.startsWith(token + '.'))) {
      unresolved.set(token, (unresolved.get(token) ?? 0) + 1);
    }
  }

  const identifiers = new Set(withoutImports.match(/\b[A-Z][A-Za-z0-9_]*\b/g) ?? []);
  const own = packageTypes.get(ownPackage);
  if (own) {
    for (const [simple, records] of own) {
      if (identifiers.has(simple)) for (const record of records) add(record.types[0], 'simple');
    }
  }
  for (const pkg of wildcardPackages) {
    for (const [simple, records] of packageTypes.get(pkg) ?? []) {
      if (!identifiers.has(simple)) continue;
      for (const record of records) add(record.types[0], 'wildcard');
    }
  }
  return { found, unresolved };
}

const references = new Map(files.map((file) => [file, referencesOf(file)]));

// === seeds и замыкание ===

const seedMode = process.argv.includes('--seeds')
  ? process.argv[process.argv.indexOf('--seeds') + 1]
  : 'all';
const seedOwners = seedMode === 'main' ? ['register']
  : seedMode === 'test' ? ['register-test']
  : ['register', 'register-test'];
const seeds = files.filter((file) => seedOwners.includes(file.owner));

/** Однопачечные ссылки seeds: файл → тип → вид ссылки и владелец типа. */
function directReferences() {
  const rows = [];
  for (const file of seeds) {
    for (const [fqn, kinds] of references.get(file).found) {
      if (fqn.startsWith('org.ipro.register.')) continue; // внутри пакета — не граница
      const owners = [...new Set(byFqn.get(fqn).map((record) => record.owner))];
      rows.push({ from: file.relative, fqn, kinds: [...kinds].sort(), owners });
    }
  }
  return rows.sort((a, b) => a.fqn.localeCompare(b.fqn));
}

/** Транзитивное замыкание обходом по объявлениям, с цепочкой от seed до первого внешнего владельца. */
function closure() {
  const visited = new Set(seeds);
  const queue = [...seeds];
  const frontier = new Map(); // файл -> {owner, fqn, kinds, from}
  const unresolved = new Map();
  while (queue.length) {
    const file = queue.shift();
    for (const [fqn, kinds] of references.get(file).found) {
      for (const record of byFqn.get(fqn)) {
        if (!frontier.has(record)) {
          frontier.set(record, {
            owner: record.owner,
            fqn,
            kinds: [...kinds].sort(),
            from: file.relative,
            fromOwner: file.owner,
          });
        }
        if (!visited.has(record)) {
          visited.add(record);
          queue.push(record);
        }
      }
    }
    for (const [token, count] of references.get(file).unresolved) {
      if (!unresolved.has(token)) unresolved.set(token, { count: 0, from: file.relative, owner: file.owner });
      unresolved.get(token).count += count;
    }
  }
  return { visited, frontier, unresolved };
}

const direct = directReferences();
const { visited, frontier, unresolved } = closure();

/** Потребители движка (обратное направление): кто называет типы register вне самого пакета. */
function consumers() {
  const rows = new Map();
  for (const file of files) {
    if (file.owner === 'register' || file.owner === 'register-test') continue;
    const named = new Set();
    for (const [fqn, kinds] of references.get(file).found) {
      if (fqn.startsWith('org.ipro.register.')) named.add(`${fqn} (${[...kinds].sort().join('+')})`);
    }
    if (named.size) rows.set(file, [...named].sort());
  }
  return rows;
}

const consumerRows = consumers();

const ownerCounts = (records) => {
  const counts = new Map();
  for (const record of records) counts.set(record.owner, (counts.get(record.owner) ?? 0) + 1);
  return [...counts].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
};

const seedMain = seeds.filter((file) => file.owner === 'register');
const seedTest = seeds.filter((file) => file.owner === 'register-test');
const closureRecords = [...visited];
const crossing = closureRecords.filter((record) => record.owner.startsWith('application'));

// Строковые и конфигурационные ссылки: код их не видит, сборка и рантайм видят.
const stringReferences = [];
for (const file of seeds) {
  const hits = file.literals.filter((literal) => {
    const token = literal.replace(/\.$/, '');
    return resolve(token) && !token.startsWith('org.ipro.register');
  });
  if (hits.length) stringReferences.push({ file: file.relative, hits: [...new Set(hits)].sort() });
}
const documentationReferences = seeds
  .map((file) => ({ file: file.relative, hits: file.documented.filter((token) => resolve(token)).sort() }))
  .filter((row) => row.hits.length);

/** Файлы регистрации, называющие типы движка: import-файл стартера, factories, XML. */
const registrationFiles = [];
const registrationRoots = [
  ['app-tree', 'src/main/resources'],
  ['app-tree-test', 'src/test/resources'],
  ...platformRoots().map(({ label, dir }) => [label, dir.replace('/java', '/resources')]),
  ...siblingRoots().map(({ label, dir }) => [label, dir.replace('/java', '/resources')]),
];
for (const [label, dir] of registrationRoots) {
  const absolute = path.resolve(PROJECT, dir);
  if (!fs.existsSync(absolute)) continue;
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        continue;
      }
      if (!/\.(imports|factories|xml|properties)$/.test(entry.name)) continue;
      const hits = [...new Set(fs.readFileSync(full, 'utf8').match(/org\.(?:ip|ipro)[\w.]*/g) ?? [])]
        .filter((token) => token.startsWith('org.ipro.register'));
      if (hits.length) {
        registrationFiles.push({
          root: label,
          file: path.relative(PROJECT, full).split(path.sep).join('/'),
          hits: [...hits].sort(),
        });
      }
    }
  };
  walk(absolute);
}

/** Типы движка и внешние владельцы, которые их называют: вход для среза контрактов. */
function seedInventory() {
  const named = new Map(); // fqn -> Set<owner>
  for (const file of files) {
    if (file.owner === 'register' || file.owner === 'register-test') continue;
    for (const [fqn] of references.get(file).found) {
      if (!fqn.startsWith('org.ipro.register.')) continue;
      if (!named.has(fqn)) named.set(fqn, new Set());
      named.get(fqn).add(file.owner);
    }
  }
  return named;
}

const inventory = seedInventory();

// === отчёт ===

const duplicates = [...new Set(closureRecords.map((record) => record.types[0]))]
  .filter((fqn) => byFqn.get(fqn).length > 1)
  .sort();
const unresolvedInNamespace = [...unresolved].filter(([token]) =>
  token.startsWith('org.ip') || token.startsWith('org.ipro'));
const thirdParty = [...unresolved].length - unresolvedInNamespace.length;

const directOf = (owner) => {
  const rows = new Map();
  for (const file of seeds.filter((candidate) => candidate.owner === owner)) {
    for (const [fqn, kinds] of references.get(file).found) {
      if (fqn.startsWith('org.ipro.register.')) continue;
      const target = byFqn.get(fqn);
      const key = target.map((record) => record.owner).filter((value, index, all) => all.indexOf(value) === index)
        .sort().join(',') + '\t' + fqn.replace(/\.(?:[A-Z][\w]*)$/, '.*');
      rows.set(key, (rows.get(key) ?? 0) + 1);
    }
  }
  return [...rows].sort();
};

const consumersByOwner = new Map();
for (const [file] of consumerRows) {
  if (!consumersByOwner.has(file.owner)) consumersByOwner.set(file.owner, 0);
  consumersByOwner.set(file.owner, consumersByOwner.get(file.owner) + 1);
}

// === проверка среза: --slice <S0|S1|S2|S3|S4> ===
//
// Срез — набор файлов, которые переезжают в модуль вместе. Компиляционная замкнутость означает:
// ни один файл среза не ссылается на файл, оставшийся в дереве. Проверка отвечает не «сколько
// зависимостей», а «какие именно ссылки выйдут за границу среза», и печатает владельцев-модули —
// это и есть список зависимостей pom нового модуля.
const SLICES = {
  // D-R S0: декларация и контракт строки. Guard и scope входят не как реализация, а потому что
  // @EntityListeners прикладных строк называет guard, а валидатор требует его наличия.
  S0: [
    'org/ipro/register/RegisterMetadata.java',
    'org/ipro/register/RegisterPeriodicity.java',
    'org/ipro/register/RegisterDescriptor.java',
    'org/ipro/register/RegisterMetadataValidator.java',
    'org/ipro/register/SliceSpec.java',
    // SliceSpec.java:72 вызывает SliceSpecValidator.supportedType — наймая проверкой среза.
    'org/ipro/register/SliceSpecValidator.java',
    'org/ipro/register/RegisterPeriodListener.java',
    'org/ipro/register/RecorderType.java',
    'org/ipro/register/RecorderRef.java',
    'org/ipro/register/PeriodicRegisterEntry.java',
    'org/ipro/register/PeriodicRegisterEntryBase.java',
    'org/ipro/register/RecordedRegisterEntry.java',
    'org/ipro/register/RecordedRegisterEntryBase.java',
    'org/ipro/register/PeriodicRecordedRegisterEntryBase.java',
    'org/ipro/register/RecordedRegisterEntryWriteGuard.java',
    'org/ipro/register/RecorderSetWriteContext.java',
    'org/ipro/register/RegisterKeyChangeScope.java',
  ],
  // D-R S1: реестр и чтение. На старте S1 монолитная RegisterAutoConfiguration была включена
  // намеренно: пока она одна на весь движок, она обязана распасться по срезам, и проверка
  // показывала, на что именно она ссылалась. Срез S3 эту развилку закрыл — монолитной
  // конфигурации больше нет ни в приложении, ни в модуле, а её место заняли три срезовые
  // (RegisterReadConfiguration, RegisterKeyWriteConfiguration, RegisterRecorderSetConfiguration),
  // поэтому в списке остаётся только читающая часть.
  S1: [
    'org/ipro/register/RegisterRegistry.java',
    'org/ipro/register/RegisterStartupValidator.java',
    'org/ipro/register/RegisterReader.java',
    'org/ipro/register/RegisterSliceReader.java',
    'org/ipro/register/RegisterListFilterPhases.java',
  ],
  S2: [
    'org/ipro/register/RegisterKeyChanges.java',
    'org/ipro/register/RegisterKeyWrites.java',
    'org/ipro/register/RegisterKeyChangePermissions.java',
    'org/ipro/register/RegisterKeyWritePermissions.java',
    'org/ipro/register/RegisterKeyWriteGuardInstaller.java',
    'org/ipro/register/RegisterRowState.java',
    'org/ipro/register/RegisterWriteMutex.java',
  ],
  // D-R S3 — протокол набора владельца. Срез закрыт 2026-10-14: список сохранён как исходный
  // замер. Он называл 13 файлов, но двух из них срез не привёз — `RecorderOrderClockBootstrap` и
  // `persistence/RecorderOrderClock` уехали раньше, в S2, вместе с командой ключа; замер назвал
  // их, потому что смотрел ссылки всех файлов среза, а не только переносимых. На текущем дереве
  // `--slice S3` отвечает «файлов нет на диске»: они уехали в артефакт,
  // и смотреть их замыкание нужно в самом модуле, а не в дереве приложения. Проверять вынос
  // после закрытия среза должны гейты артефакта (`RegisterSurfaceRoleTest`,
  // `PlatformRegisterModuleTest`), а не этот замер: он отвечает «что уедет», а не «что уехало».
  S3: [
    'org/ipro/register/RecorderSetWriter.java',
    'org/ipro/register/RecorderSetWriteRequest.java',
    'org/ipro/register/RecorderSetWriteAuthorizer.java',
    'org/ipro/register/RecorderSetContribution.java',
    'org/ipro/register/RecorderSetContributionParticipant.java',
    'org/ipro/register/RecorderSetDeleteParticipant.java',
    'org/ipro/register/RecorderSetTransactionExecutor.java',
    'org/ipro/register/RegisterRecords.java',
    'org/ipro/register/RecorderTypeRegistry.java',
    'org/ipro/register/RecordedRegisterReader.java',
    'org/ipro/register/RlsRecorderSetWriteAuthorizer.java',
    'org/ipro/register/RecorderOrderClockBootstrap.java',
    'org/ipro/register/persistence/RecorderOrderClock.java',
  ],
  // D-R S4: стандартный интерфейс (platform-register-vaadin). Пакет приложения, но прикладных
  // типов в этих классах нет — проверяется тем же способом.
  S4: [
    'org/ip/views/register/RegisterFormCustomization.java',
    'org/ip/views/register/RegisterKeyDialog.java',
    'org/ip/views/register/RecorderRefDisplay.java',
    'org/ip/views/register/RecorderRefPresentation.java',
    'org/ip/views/register/RecorderRefPresentationResolver.java',
  ],
};

const SLICE_ORDER = Object.keys(SLICES);

/**
 * Срез проверяется в порядке выноса: ссылка на уже перенесённый срез — не блокер (он в том же
 * или в нижнем модуле), а ссылка на более поздний срез или на дерево — блокер.
 */
function sliceClosure(name) {
  const index = SLICE_ORDER.indexOf(name);
  const declared = SLICES[name];
  if (index < 0) {
    console.error(`Неизвестный срез: ${name}. Известные: ${SLICE_ORDER.join(', ')}`);
    process.exit(2);
  }
  const wanted = new Set(SLICE_ORDER.slice(0, index + 1).flatMap((key) => SLICES[key]));
  const laterOf = new Map();
  for (const key of SLICE_ORDER.slice(index + 1)) {
    for (const path of SLICES[key]) laterOf.set(path, key);
  }
  const members = files.filter((file) => declared.includes(file.relative));
  const missing = declared.filter((path) => !members.some((file) => file.relative === path));
  const escaping = [];
  const allowed = new Map();
  const unresolvedTokens = new Map();
  for (const file of members) {
    for (const [fqn, kinds] of references.get(file).found) {
      const records = byFqn.get(fqn) ?? [];
      if (records.some((record) => wanted.has(record.relative))) continue;
      // Осталось в дереве: сам пакет регистра, приложение или платформенный код, ещё живущий в дереве.
      const inner = records.filter((record) => record.owner === 'register'
        || record.owner === 'register-test'
        || record.owner.startsWith('application')
        || record.owner.startsWith('platform-in-tree'));
      if (inner.length) {
        escaping.push({
          from: file.relative,
          fqn,
          kinds: [...kinds].sort(),
          targets: inner.map((record) => `${record.owner}:${record.relative}`
            + (laterOf.has(record.relative) ? ` [срез ${laterOf.get(record.relative)}]` : '')),
        });
      }
      for (const record of records) {
        if (!allowed.has(record.owner)) allowed.set(record.owner, new Set());
        allowed.get(record.owner).add(fqn);
      }
    }
    for (const [token] of references.get(file).unresolved) {
      if (token.startsWith('org.ipro.register')) unresolvedTokens.set(token, file.relative);
    }
  }
  return { name, members, missing, escaping, allowed, unresolvedTokens };
}

const sliceArgument = process.argv.includes('--slice')
  ? process.argv[process.argv.indexOf('--slice') + 1]
  : null;
if (sliceArgument) {
  const result = sliceClosure(sliceArgument);
  const position = SLICE_ORDER.indexOf(result.name) + 1;
  console.log(`# Срез ${result.name} (${position} из ${SLICE_ORDER.length}): ${result.members.length} файлов`);
  console.log(`# Уже перенесено более ранними срезами: ${SLICE_ORDER.slice(0, position - 1).join(', ') || 'ничего'}`);
  for (const file of result.members.sort((a, b) => a.relative.localeCompare(b.relative))) {
    console.log(`${file.relative}\t${file.types.join(', ')}`);
  }
  console.log(`\n## Файлы среза, которых нет на диске: ${result.missing.length}`);
  for (const path of result.missing) console.log(path);
  console.log(`\n## Ссылки за границу среза: ${result.escaping.length}`);
  for (const row of result.escaping.sort((a, b) => a.from.localeCompare(b.from) || a.fqn.localeCompare(b.fqn))) {
    console.log(`${row.from}\t${row.fqn}\t${row.kinds.join('+')}\t${row.targets.join(', ')}`);
  }
  if (!result.escaping.length) console.log('нет: срез компиляционно замкнут');
  console.log(`\n## Владельцы, нужные срезу (зависимости pom):`);
  for (const [owner, types] of [...result.allowed].sort((a, b) => a[0].localeCompare(b[0]))) {
    console.log(`${owner}\t${types.size} типов\t${[...types].sort().join(', ')}`);
  }
  console.log(`\n## Неразрешённые ссылки на org.ipro.register.*: ${result.unresolvedTokens.size}`);
  for (const [token, from] of [...result.unresolvedTokens].sort()) console.log(`${token}\t${from}`);
  process.exit(result.escaping.length || result.missing.length ? 1 : 0);
}

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({
    seeds: { main: seedMain.length, test: seedTest.length },
    direct: direct.map(({ from, fqn, kinds, owners }) => ({ from, fqn, kinds, owners })),
    closure: {
      files: closureRecords.length,
      byOwner: Object.fromEntries(ownerCounts(closureRecords)),
      application: crossing.map((record) => ({
        fqn: record.types[0],
        file: record.relative,
        from: frontier.get(record)?.from ?? null,
        fromOwner: frontier.get(record)?.fromOwner ?? null,
        kinds: frontier.get(record)?.kinds ?? [],
      })),
    },
    consumers: [...consumerRows].map(([file, named]) => ({ owner: file.owner, file: file.relative, named })),
    inventory: Object.fromEntries([...inventory].map(([fqn, owners]) => [fqn, [...owners].sort()])),
    unresolved: unresolvedInNamespace.map(([token, value]) => ({ token, ...value })),
    thirdPartyUnresolved: thirdParty,
    stringReferences,
    documentationReferences,
    registrationFiles,
    splitPackages: duplicates,
  }, null, 2));
  process.exit(0);
}

console.log('# Замыкание org.ipro.register (seeds: ' + seedMode + ')');
console.log(`# файлов-семян: ${seedMain.length} main + ${seedTest.length} test`);
console.log(`# корней сканирования: ${ROOTS.length} (${ROOTS.map((root) => root.label).join(', ')})`);
console.log(`# объявлений типов в воркспейсе: ${byFqn.size}`);

console.log(`\n## Однопачечные ссылки из main-семян (сам движок): ` + directOf('register').length);
for (const [key] of directOf('register')) console.log(key);
console.log(`\n## Однопачечные ссылки из test-семян (тесты движка): ` + directOf('register-test').length);
for (const [key] of directOf('register-test')) console.log(key);

console.log(`\n## Транзитивное замыкание: ${closureRecords.length} файлов`);
for (const [owner, count] of ownerCounts(closureRecords)) console.log(`${owner}\t${count}`);

console.log(`\n## Пересечение границы в приложение: ${crossing.length}`);
for (const record of crossing) {
  const edge = frontier.get(record);
  console.log(`${record.types[0]}\t${record.owner}\t${record.relative}\tиз ${edge.fromOwner}:${edge.from}\t${edge.kinds.join('+')}`);
}
if (!crossing.length) console.log('нет: движок и его тесты не называют прикладных типов');

console.log(`\n## Потребители движка (обратное направление): ${consumerRows.size} файлов`);
for (const [owner, count] of [...consumersByOwner].sort((a, b) => b[1] - a[1])) console.log(`${owner}\t${count}`);
for (const [file, named] of [...consumerRows].sort((a, b) => a[0].relative.localeCompare(b[0].relative))) {
  console.log(`${file.owner}\t${file.relative}\t${named.join('; ')}`);
}

console.log(`\n## Типы движка, названные вне пакета: ${inventory.size}`);
for (const [fqn, owners] of [...inventory].sort()) console.log(`${fqn}\t${[...owners].sort().join(',')}`);

console.log(`\n## Неразрешённые ссылки в пространстве имён проекта: ${unresolvedInNamespace.length}`);
for (const [token, value] of unresolvedInNamespace.sort()) {
  console.log(`${token}\t${value.count}\t${value.owner}:${value.from}`);
}
console.log(`## Прочие неразрешённые (сторонние библиотеки и JDK): ${thirdParty}`);

console.log(`\n## Ссылки в строковых литералах (рефлексия, имена в тексте): ${stringReferences.length} файлов`);
for (const row of stringReferences) console.log(`${row.file}\t${row.hits.join(', ')}`);

console.log(`\n## Ссылки в javadoc (документация, не зависимость): ${documentationReferences.length} файлов`);
for (const row of documentationReferences) console.log(`${row.file}\t${row.hits.join(', ')}`);

console.log(`\n## Файлы регистрации, называющие движок: ${registrationFiles.length}`);
for (const row of registrationFiles) console.log(`${row.root}\t${row.file}\t${row.hits.join(', ')}`);

console.log(`\n## Split package в замыкании: ${duplicates.length}`);
for (const fqn of duplicates) {
  console.log(`${fqn}\t${byFqn.get(fqn).map((record) => record.owner).join(', ')}`);
}
