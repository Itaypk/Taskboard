import { afterEach, describe, expect, it } from 'vitest';
import { capturePrerendered, prerenderedHtml } from './prerendered';

function rootWith(html: string): HTMLElement {
    const root = document.createElement('div');
    root.innerHTML = html;
    return root;
}

describe('prerendered content page', () => {
    afterEach(() => window.history.replaceState(null, '', '/'));

    it('keeps the server-rendered markup for the path it was rendered for', () => {
        window.history.replaceState(null, '', '/terms');
        capturePrerendered(rootWith('<main><h1>Terms of Service</h1></main>'));

        expect(prerenderedHtml('/terms')).toBe('<main><h1>Terms of Service</h1></main>');
        expect(prerenderedHtml('/privacy')).toBeNull();
    });

    it('captures nothing from an empty root', () => {
        window.history.replaceState(null, '', '/terms');
        capturePrerendered(rootWith(''));

        expect(prerenderedHtml('/terms')).toBeNull();
    });
});
