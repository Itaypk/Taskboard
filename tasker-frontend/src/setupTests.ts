import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import '@testing-library/jest-dom/vitest';
import './i18n';

// `globals` is off in the Vitest config, so React Testing Library's automatic cleanup never
// registers itself. Without this, every `render` in a file stacks another live tree in the same
// jsdom — harmless for a one-case file, but it makes `getByRole` ambiguous and leaves earlier
// components still listening to window events.
afterEach(cleanup);
