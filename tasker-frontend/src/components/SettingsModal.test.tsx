import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_SETTINGS } from '../data';
import type { UserSettings } from '../types';

const { updateUserSettings, fetchSettingsOptions, fetchIdentities, applyLocale } = vi.hoisted(() => ({
  updateUserSettings: vi.fn(),
  fetchSettingsOptions: vi.fn(),
  fetchIdentities: vi.fn(),
  applyLocale: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, updateUserSettings, fetchSettingsOptions };
});
vi.mock('../auth/authApi', async () => {
  const actual = await vi.importActual<typeof import('../auth/authApi')>('../auth/authApi');
  return { ...actual, fetchIdentities };
});
vi.mock('../i18n', async () => {
  const actual = await vi.importActual<typeof import('../i18n')>('../i18n');
  return { ...actual, applyLocale };
});
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: { telegramChatReady: true } } }),
}));

import { SettingsModal } from './SettingsModal';
import type { SettingsSection } from '../taskLink';

const settings: UserSettings = { ...DEFAULT_SETTINGS, displayName: 'Dana', timeZone: 'Europe/London' };

function renderModal(section: SettingsSection | null, onSave = vi.fn(), onNavigate = vi.fn()) {
  render(
    <SettingsModal
      settings={settings}
      open
      section={section}
      onNavigate={onNavigate}
      onClose={vi.fn()}
      onSave={onSave}
      onAccountDeleted={vi.fn()}
    />,
  );
  return { onSave, onNavigate };
}

/** jsdom has no matchMedia; a stub that answers the phone breakpoint either way. */
function setPhone(matches: boolean) {
  window.matchMedia = vi.fn().mockReturnValue({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  vi.clearAllMocks();
  updateUserSettings.mockImplementation(async payload => payload);
  fetchSettingsOptions.mockResolvedValue({
    timeZones: ['UTC', 'Europe/London', 'Asia/Jerusalem'],
    languages: [{ code: 'en', label: 'English' }, { code: 'he', label: 'עברית' }],
    genders: [],
  });
  fetchIdentities.mockResolvedValue([{ provider: 'telegram', linkedAt: null, lastLoginAt: null }]);
  setPhone(false);
});
afterEach(() => {
  // @ts-expect-error -- restore jsdom's default (no matchMedia).
  delete window.matchMedia;
});

describe('SettingsModal', () => {
  it('shows the section rail with the open section marked', () => {
    renderModal('reminders');

    const rail = screen.getByRole('navigation');
    expect(within(rail).getByRole('button', { name: 'Reminders & digest' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Reminders & digest' })).toBeInTheDocument();
  });

  it('opens the first section for a bare /settings on a wide screen', () => {
    renderModal(null);
    expect(screen.getByRole('heading', { name: 'General' })).toBeInTheDocument();
  });

  it('moves between sections through the route', () => {
    const { onNavigate } = renderModal('general');
    fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Calendar' }));
    expect(onNavigate).toHaveBeenCalledWith('calendar');
  });

  it('saves a toggle as soon as it flips, with the full settings payload', async () => {
    const { onSave } = renderModal('reminders');

    fireEvent.click(screen.getByLabelText('Send a daily digest'));

    await waitFor(() => expect(updateUserSettings).toHaveBeenCalledTimes(1));
    expect(updateUserSettings.mock.calls[0][0]).toMatchObject({ dailyDigestEnabled: false, displayName: 'Dana' });
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ dailyDigestEnabled: false })));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved');
  });

  it('saves a text field when it loses focus, not on every keystroke', async () => {
    renderModal('general');
    const name = screen.getByLabelText('Display name');

    fireEvent.change(name, { target: { value: 'Dana K' } });
    expect(updateUserSettings).not.toHaveBeenCalled();

    fireEvent.blur(name);
    await waitFor(() => expect(updateUserSettings).toHaveBeenCalledTimes(1));
    expect(updateUserSettings.mock.calls[0][0]).toMatchObject({ displayName: 'Dana K' });
  });

  it('skips a save that changes nothing', async () => {
    renderModal('general');
    fireEvent.blur(screen.getByLabelText('Display name'));
    await act(async () => {});
    expect(updateUserSettings).not.toHaveBeenCalled();
  });

  it('puts back a time zone the server would reject instead of saving it', async () => {
    renderModal('general');
    await waitFor(() => expect(fetchSettingsOptions).toHaveBeenCalled());
    await act(async () => {});
    const tz = screen.getByLabelText('Time zone');

    fireEvent.change(tz, { target: { value: 'Europe/Lond' } });
    fireEvent.blur(tz);

    expect(tz).toHaveValue('Europe/London');
    await act(async () => {});
    expect(updateUserSettings).not.toHaveBeenCalled();
  });

  it('applies a language change once it is saved', async () => {
    renderModal('general');
    await screen.findByRole('option', { name: 'עברית' });

    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'he' } });

    await waitFor(() => expect(applyLocale).toHaveBeenCalledWith('he'));
  });

  describe('on a phone', () => {
    beforeEach(() => setPhone(true));

    it('lists the sections with what is set in each', async () => {
      const { onNavigate } = renderModal(null);

      const general = screen.getByRole('button', { name: /General/ });
      expect(general).toHaveTextContent('Dana');
      expect(general).toHaveTextContent('Europe/London');
      expect(await screen.findByRole('button', { name: /Sign-in & devices/ })).toHaveTextContent('Telegram');
      expect(screen.getByRole('button', { name: /Reminders & digest/ })).toHaveTextContent('Reminders on');

      fireEvent.click(general);
      expect(onNavigate).toHaveBeenCalledWith('general');
    });

    it('goes back from a section to the list', () => {
      const { onNavigate } = renderModal('general');
      fireEvent.click(screen.getByRole('button', { name: /Settings/ }));
      expect(onNavigate).toHaveBeenCalledWith(null);
    });
  });
});
