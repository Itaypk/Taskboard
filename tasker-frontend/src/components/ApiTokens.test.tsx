import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiTokens } from './ApiTokens';

const { fetchApiTokens, createApiToken, revokeApiToken } = vi.hoisted(() => ({
  fetchApiTokens: vi.fn(),
  createApiToken: vi.fn(),
  revokeApiToken: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, fetchApiTokens, createApiToken, revokeApiToken };
});

const existingToken = {
  id: 'tok-1',
  name: 'Claude Code',
  prefix: 'blf_a1b2c3d4',
  scope: 'write' as const,
  createdAt: '2026-08-01T10:00:00Z',
  lastUsedAt: null,
  expiresAt: null,
};

async function createToken(name: string) {
  fireEvent.change(await screen.findByPlaceholderText(/What is this for/), { target: { value: name } });
  fireEvent.click(screen.getByRole('button', { name: 'Create token' }));
}

describe('ApiTokens', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchApiTokens.mockResolvedValue([existingToken]);
  });

  afterEach(cleanup);

  it('lists existing tokens by name and prefix', async () => {
    render(<ApiTokens />);

    expect(await screen.findByText('Claude Code')).toBeInTheDocument();
    expect(screen.getByText(/blf_a1b2c3d4/)).toBeInTheDocument();
    // A token that has never been used says so rather than rendering an empty date.
    expect(screen.getByText(/never used/)).toBeInTheDocument();
  });

  it('shows the plaintext secret once after creating, with a copy-now warning', async () => {
    fetchApiTokens.mockResolvedValue([]);
    createApiToken.mockResolvedValue({
      token: 'blf_supersecretvalue',
      apiToken: { ...existingToken, name: 'Scripts' },
    });
    render(<ApiTokens />);

    await createToken('Scripts');

    expect(await screen.findByText('blf_supersecretvalue')).toBeInTheDocument();
    expect(screen.getByText(/won't be shown again/)).toBeInTheDocument();
    // 90 days is the default lifetime.
    expect(createApiToken).toHaveBeenCalledWith('Scripts', 'write', 90);
  });

  it('mints a non-expiring token when the user picks no expiration', async () => {
    fetchApiTokens.mockResolvedValue([]);
    createApiToken.mockResolvedValue({ token: 'blf_x', apiToken: { ...existingToken, name: 'Cron' } });
    render(<ApiTokens />);

    fireEvent.change(await screen.findByRole('combobox', { name: 'Expiry' }), { target: { value: 'never' } });
    await createToken('Cron');

    await waitFor(() => expect(createApiToken).toHaveBeenCalledWith('Cron', 'write', null));
  });

  it('shows when each token expires, and which already have', async () => {
    fetchApiTokens.mockResolvedValue([
      existingToken,
      { ...existingToken, id: 'tok-2', name: 'Soon', expiresAt: '2999-01-01T00:00:00Z' },
      { ...existingToken, id: 'tok-3', name: 'Old', expiresAt: '2000-01-01T00:00:00Z' },
    ]);
    render(<ApiTokens />);

    expect(await screen.findByText(/never expires/)).toBeInTheDocument();
    expect(screen.getByText(/expires \S/)).toBeInTheDocument();
    expect(screen.getByText(/expired \S/)).toBeInTheDocument();
  });

  it('does not count expired tokens toward the limit', async () => {
    const expired = Array.from({ length: 5 }, (_, i) => ({
      ...existingToken, id: `old-${i}`, name: `Old ${i}`, expiresAt: '2000-01-01T00:00:00Z',
    }));
    fetchApiTokens.mockResolvedValue(expired);
    render(<ApiTokens />);

    fireEvent.change(await screen.findByPlaceholderText(/What is this for/), { target: { value: 'New' } });

    expect(screen.getByRole('button', { name: 'Create token' })).toBeEnabled();
  });

  it('hides the secret again once dismissed, since it cannot be re-fetched', async () => {
    fetchApiTokens.mockResolvedValue([]);
    createApiToken.mockResolvedValue({
      token: 'blf_supersecretvalue',
      apiToken: { ...existingToken, name: 'Scripts' },
    });
    render(<ApiTokens />);

    await createToken('Scripts');
    fireEvent.click(await screen.findByRole('button', { name: 'Done' }));

    await waitFor(() => {
      expect(screen.queryByText('blf_supersecretvalue')).not.toBeInTheDocument();
    });
  });

  it('revokes a token after confirmation and drops it from the list', async () => {
    revokeApiToken.mockResolvedValue(undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<ApiTokens />);

    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));

    await waitFor(() => expect(revokeApiToken).toHaveBeenCalledWith('tok-1'));
    await waitFor(() => expect(screen.queryByText('Claude Code')).not.toBeInTheDocument());
  });

  it('does not revoke when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<ApiTokens />);

    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));

    expect(revokeApiToken).not.toHaveBeenCalled();
    expect(screen.getByText('Claude Code')).toBeInTheDocument();
  });
});
