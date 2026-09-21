import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { lazyComponent, useLazyComponent } from './lazyComponent';

function Host({ chunk }: { chunk: ReturnType<typeof lazyComponent<{ label: string }>> }) {
    const { component: Loaded, failed } = useLazyComponent(chunk);
    if (failed) return <p>failed</p>;
    if (!Loaded) return <p>placeholder</p>;
    return <Loaded label="ready" />;
}

describe('useLazyComponent', () => {
    it('shows the placeholder, then the component, without a Suspense boundary', async () => {
        const chunk = lazyComponent<{ label: string }>(() =>
            Promise.resolve({ default: ({ label }: { label: string }) => <p>{label}</p> }),
        );

        render(<Host chunk={chunk} />);
        expect(screen.getByText('placeholder')).toBeInTheDocument();
        expect(await screen.findByText('ready')).toBeInTheDocument();
    });

    it('reports a failed chunk instead of retrying forever', async () => {
        const load = vi.fn(() => Promise.reject(new Error('404')));
        const chunk = lazyComponent<{ label: string }>(load);

        const { rerender } = render(<Host chunk={chunk} />);
        expect(await screen.findByText('failed')).toBeInTheDocument();

        rerender(<Host chunk={chunk} />);
        expect(load).toHaveBeenCalledTimes(1);
    });

    it('loads the chunk once across several mounted consumers', async () => {
        const load = vi.fn(() =>
            Promise.resolve({ default: ({ label }: { label: string }) => <p>{label}</p> }),
        );
        const chunk = lazyComponent<{ label: string }>(load);

        render(<><Host chunk={chunk} /><Host chunk={chunk} /></>);
        expect(await screen.findAllByText('ready')).toHaveLength(2);
        expect(load).toHaveBeenCalledTimes(1);
    });
});
