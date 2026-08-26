/// <reference types="node" />
// This test reads stylesheets off disk. Vitest's CSS pipeline hands `import.meta.glob` a class-name
// object for `*.module.css` (and empty strings elsewhere) even with `?raw`, so the filesystem is the
// only faithful source. The reference above scopes Node's types to this file rather than adding
// them to `tsconfig.app.json`, which exists to keep Node globals out of browser code.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Guards the CSS half of the RTL pass (docs/I18N.md, D5). Layout mirrors for Hebrew and Arabic
 * only as long as spacing and offsets are expressed as *logical* properties, so a stray
 * `margin-left` in a new component is a silent RTL bug that no unit test would otherwise catch —
 * it looks perfectly correct in English.
 *
 * A handful of declarations must stay physical: artwork that bakes a light source into a gradient
 * (peeled paper corners), decorative scatter tuned against fixed art, the mascot, and `left: 50%`
 * centering, which is direction-neutral. Those are enumerated below, each already carrying an
 * explanatory comment at its own site. The list is deliberately exact rather than a per-file
 * budget: adding a physical property means adding a line here and justifying it in review.
 */

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..');

/** `float`/`clear` are included because their logical values (`inline-start`) mirror too. */
const PHYSICAL = /(?<![-\w])(?:(?:margin|padding|border)-(?:left|right)|left|right|float|clear)\s*:\s*[^;{}]+/gi;

const ALLOWED = [
    // Peeled-corner artwork: a 135deg/315deg gradient pair with a baked top-left light source.
    'auth/EmailLoginConfirmPage.module.css :: right: 0',
    'auth/LoginPage.module.css :: right: 0',
    'auth/LoginPage.module.css :: right: 0',
    'components/TaskLine.module.css :: right: 0',
    'components/TaskLine.module.css :: right: 0',
    'index.css :: right: 0',
    // Decorative satellite notes, positioned against the mascot's fixed corner.
    'auth/LoginPage.module.css :: left: 0',
    'auth/LoginPage.module.css :: left: 2%',
    'auth/LoginPage.module.css :: right: 0',
    'auth/LoginPage.module.css :: right: 1%',
    // The mascot itself, plus its two responsive overrides.
    'index.css :: right: 24px',
    'index.css :: right: 12px',
    'index.css :: right: 6px',
    // Symmetric embossed dots on the logo tape.
    'index.css :: left: 4px',
    'index.css :: right: 4px',
    // Direction-neutral horizontal centring (paired with translateX(-50%)).
    'auth/EmailLoginConfirmPage.module.css :: left: 50%',
    'components/UpdateBanner.module.css :: left: 50%',
    'index.css :: left: 50%',
    'index.css :: left: 50%',
].sort();

function cssFiles(dir: string): string[] {
    return readdirSync(dir).flatMap((entry: string) => {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) return cssFiles(path);
        return path.endsWith('.css') ? [path] : [];
    });
}

/** Blanks out comments so a property named inside prose is not mistaken for a declaration. */
function stripComments(css: string): string {
    return css.replace(/\/\*[\s\S]*?\*\//g, (m) => ' '.repeat(m.length));
}

function findPhysical(): string[] {
    const found: string[] = [];
    for (const file of cssFiles(SRC)) {
        const rel = file.slice(SRC.length + 1).replaceAll('\\', '/');
        for (const match of stripComments(readFileSync(file, 'utf8')).matchAll(PHYSICAL)) {
            const declaration = match[0].split(/\s+/).join(' ').trim();
            // `float: inline-start` is already the logical form; the regex catches the property name.
            if (/^(float|clear):\s*inline-/i.test(declaration)) continue;
            found.push(`${rel} :: ${declaration}`);
        }
    }
    return found.sort();
}

describe('CSS uses logical properties so the UI mirrors in RTL', () => {
    it('has no physical directional declarations beyond the documented exceptions', () => {
        expect(findPhysical()).toEqual(ALLOWED);
    });

    it('scans a plausible number of stylesheets', () => {
        // Cheap canary: a broken glob would make the assertion above pass vacuously.
        expect(cssFiles(SRC).length).toBeGreaterThan(25);
    });
});
