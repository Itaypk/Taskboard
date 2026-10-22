import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InboundMailAddress } from './InboundMailAddress';

const { fetchInboundMailAddress, rotateInboundMailAddress, revokeInboundMailAddress } = vi.hoisted(() => ({
  fetchInboundMailAddress: vi.fn(),
  rotateInboundMailAddress: vi.fn(),
  revokeInboundMailAddress: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, fetchInboundMailAddress, rotateInboundMailAddress, revokeInboundMailAddress };
});

const current = { address: 'tasks.k7f3x5q2mz@m.example.com', createdAt: '2026-10-22T12:00:00Z' };
const none = { address: null, createdAt: null };

describe('InboundMailAddress', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(cleanup);

  it('offers to create an address when there is none', async () => {
    fetchInboundMailAddress.mockResolvedValue(none);
    rotateInboundMailAddress.mockResolvedValue(current);
    render(<InboundMailAddress />);

    fireEvent.click(await screen.findByRole('button', { name: 'Create my address' }));

    expect(await screen.findByText(current.address)).toBeInTheDocument();
    // Creating needs no confirmation: there is no old address to break.
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it('shows the address and replaces it after confirming a change', async () => {
    fetchInboundMailAddress.mockResolvedValue(current);
    rotateInboundMailAddress.mockResolvedValue({ ...current, address: 'tasks.abcdefgh23@m.example.com' });
    render(<InboundMailAddress />);

    expect(await screen.findByText(current.address)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Change address' }));

    expect(await screen.findByText('tasks.abcdefgh23@m.example.com')).toBeInTheDocument();
    expect(window.confirm).toHaveBeenCalled();
  });

  it('does nothing when a change is not confirmed', async () => {
    vi.mocked(window.confirm).mockReturnValue(false);
    fetchInboundMailAddress.mockResolvedValue(current);
    render(<InboundMailAddress />);

    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));

    expect(revokeInboundMailAddress).not.toHaveBeenCalled();
    expect(screen.getByText(current.address)).toBeInTheDocument();
  });

  it('turns the address off', async () => {
    fetchInboundMailAddress.mockResolvedValue(current);
    revokeInboundMailAddress.mockResolvedValue(undefined);
    render(<InboundMailAddress />);

    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));

    expect(await screen.findByRole('button', { name: 'Create my address' })).toBeInTheDocument();
    expect(screen.queryByText(current.address)).not.toBeInTheDocument();
  });
});
