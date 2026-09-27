/*
 * D3.6.5: копии редактора параметров удалены, их держательские записи должны уйти из реестра
 * поверхностей. Скрипт печатает, что именно снял, а не «поправил молча».
 */
const fs = require('fs');

const target = 'src/test/resources/platform-public-surface.txt';
const holders = [
  'org/ip/views/reportstudio/compact/ReportParamEditorCompact.java',
  'org/ip/views/reportstudio/structured/ReportParamEditorStructured.java',
];

const lines = fs.readFileSync(target, 'utf8').split('\n');
let removed = 0;
const touched = [];
const result = lines.map((line, index) => {
  let updated = line;
  for (const holder of holders) {
    while (updated.includes(` ${holder}`)) {
      updated = updated.replace(` ${holder}`, '');
      removed++;
    }
  }
  if (updated !== line) {
    touched.push(index + 1);
  }
  return updated;
});

fs.writeFileSync(target, result.join('\n'));
console.log(`removed entries: ${removed}`);
console.log(`touched lines: ${touched.join(', ')}`);
