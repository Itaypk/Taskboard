#!/usr/bin/env node
// Heuristic inventory of hardcoded user-facing English strings across tasker-frontend/src.
// Not a precise extraction tool -- just enough signal to size up remaining i18n work per
// component. Run from tasker-frontend/: `node ../tools/i18n-inventory.mjs`.
// Output feeds docs/I18N-INVENTORY.md; re-run and diff after each extraction PR.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const root = path.resolve(process.cwd(), 'src');

const files = execSync(`find ${root} -name "*.tsx" -not -name "*.test.tsx"`, { encoding: 'utf8' })
    .split('\n')
    .filter(Boolean)
    .sort();

const CODE_TOKENS = [
    'useState', 'useEffect', 'useRef', 'useMemo', 'useCallback', 'useContext',
    '=>', 'const ', 'let ', 'return ', 'styles.', 'className', 'import ', 'export ',
    '.map(', '.filter(', '.find(', '.reduce(', 'Promise', 'void', ' = ', ';', '&&', '||',
    '===', '!==', '  ', '(); ', 'setActive', 'useTranslation', 'React.', 'e.g.',
];

const hasLetters = (s) => /[A-Za-z]{2,}/.test(s);

function looksLikeCode(s) {
    const t = s.trim();
    if (!t) return true;
    if (CODE_TOKENS.some((tok) => t.includes(tok))) return true;
    if (/^[a-z0-9_-]+$/.test(t) && !t.includes(' ')) return true; // single lowercase token
    if (/^[A-Z0-9_]+$/.test(t)) return true; // CONSTANT
    if (/^https?:\/\//.test(t)) return true;
    if (/^#[0-9a-fA-F]{3,8}$/.test(t)) return true;
    if (/[{}]/.test(t)) return true; // stray braces = still inside an expression
    // letters-to-total ratio guard against symbol soup
    const letters = (t.match(/[A-Za-z]/g) || []).length;
    if (letters / t.length < 0.5) return true;
    return false;
}

const results = [];

for (const file of files) {
    const src = readFileSync(file, 'utf8');
    const rel = path.relative(process.cwd(), file);

    const hits = new Set();

    // JSX text content between tags: >text< (may be indented on its own line, so allow newlines
    // as long as there's no nested tag/expression in between).
    for (const m of src.matchAll(/>([^<>{}]{2,200})</g)) {
        const t = m[1].replace(/\s+/g, ' ').trim();
        if (t && hasLetters(t) && !looksLikeCode(t)) hits.add(t);
    }

    // Common user-facing attributes
    for (const m of src.matchAll(/\b(placeholder|title|aria-label|alt)=\{?"([^"]+)"\}?/g)) {
        const t = m[2].trim();
        if (t && hasLetters(t) && !looksLikeCode(t)) hits.add(t);
    }

    // Single-line template literals that read like sentences/messages
    for (const m of src.matchAll(/`([^`\n]{3,})`/g)) {
        const t = m[1].trim();
        if (t.includes(' ') && hasLetters(t) && !looksLikeCode(t)) hits.add(t);
    }

    // Plain quoted string literals (object values, const maps, JSX attr values via {}) that read
    // like sentences: 3+ words, not an import path / CSS selector / regex-ish token.
    for (const m of src.matchAll(/'([^'\n]{6,})'|"([^"\n]{6,})"/g)) {
        const t = (m[1] ?? m[2]).trim();
        const words = t.split(/\s+/).filter(Boolean);
        if (words.length < 3) continue;
        if (/^[./#]/.test(t)) continue; // path or CSS selector
        if (hasLetters(t) && !looksLikeCode(t)) hits.add(t);
    }

    if (hits.size > 0) {
        results.push({ file: rel, count: hits.size, samples: [...hits].slice(0, 4) });
    }
}

results.sort((a, b) => b.count - a.count);

console.log('| Component | ~Strings | Sample |');
console.log('| --- | --- | --- |');
for (const r of results) {
    const sample = r.samples.map((s) => s.replace(/\|/g, '\\|').slice(0, 60)).join('; ');
    console.log(`| ${r.file} | ${r.count} | ${sample} |`);
}

console.log(`\nTotal components with hardcoded copy: ${results.length}`);
console.log(`Total ~strings: ${results.reduce((a, r) => a + r.count, 0)}`);
