/*
 * D3.6.5: один scope вместо двух.
 * 1) Живая часть structured-scope (белая основа и две цветовые переменные) переезжает в .report-editor.
 * 2) Сам scope снимается целиком: пять его селекторов (card-group, card-selected, pill-toggle,
 *    drop-line, drop-overlay) не используются ни одним элементом кода — проверено grep по src/main.
 * Скрипт печатает, что снял, и проверяет баланс скобок, чтобы не оставить битый CSS.
 */
const fs = require('fs');

const path = 'src/main/frontend/themes/default/styles.css';
const source = fs.readFileSync(path, 'utf8');
const lines = source.split('\n');

const anchor = '  --vaadin-grid-cell-padding: 0.25rem 0.5rem;';
const firstAnchor = lines.findIndex(line => line.includes(anchor));
if (firstAnchor < 0) {
  throw new Error('не найден якорь переменных .report-editor');
}

const scopeStart = lines.findIndex(line => line.trim().startsWith('.report-editor-structured {'));
if (scopeStart < 0) {
  throw new Error('не найден scope .report-editor-structured');
}

const merged = [
  ...lines.slice(0, firstAnchor + 1),
  '  /* D3.6.5: перенесено из снятого scope .report-editor-structured — компактные размеры плюс',
  '     белая основа, которая раньше приходила только от вложенного редактора структуры. Теперь',
  '     это вид всего экрана редактора, а не одной вкладки: второй scope держал те же правила. */',
  '  --lumo-base-color: #ffffff;',
  '  --lumo-contrast-5pct: #f5f7f9;',
  '  background: #ffffff;',
  ...lines.slice(firstAnchor + 1, scopeStart - 1),
  '',
];

const result = merged.join('\n');
fs.writeFileSync(path, result);

const open = (result.match(/\{/g) || []).length;
const close = (result.match(/\}/g) || []).length;
console.log(`removed scope lines: ${lines.length - merged.length}`);
console.log(`braces: open=${open} close=${close} balanced=${open === close}`);
console.log(`structured scope left: ${result.includes('.report-editor-structured')}`);
