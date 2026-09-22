import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { WeeklyPlanDrawer } from './WeeklyPlanDrawer';

// The drawer fetches its overview on open; stub the two calls it makes there. `plannableTaskCount`
// is the field under test — it decides whether the two "Plan …" buttons are offered at all.
const fetchPlanningEntry = vi.fn();
const startPlanning = vi.fn();
const replyPlanning = vi.fn();
vi.mock('../api', () => ({
  ApiError: class extends Error {},
  fetchPlanningEntry: () => fetchPlanningEntry(),
  fetchPlans: () => Promise.resolve([]),
  fetchPlanningTranscript: () => Promise.resolve(null),
  fetchPlanForWeek: () => Promise.resolve(null),
  fetchEventsForWeek: () => Promise.resolve([]),
  startPlanning: (...args: unknown[]) => startPlanning(...args),
  replyPlanning: (...args: unknown[]) => replyPlanning(...args),
  revisePlanning: vi.fn(),
  abandonPlanning: vi.fn(),
}));

const entry = (plannableTaskCount: number) => ({
  activeSessionId: null,
  completedPlanSummary: null,
  revisableSessionId: null,
  thisWeek: { weekStart: '2026-08-31', weekEnd: '2026-09-06' },
  nextWeek: { weekStart: '2026-09-07', weekEnd: '2026-09-13' },
  aiAvailable: true,
  plannableTaskCount,
});

// Router context because assistant bubbles render through MarkdownRenderer, which links internally.
function renderDrawer() {
  return render(
    <MemoryRouter>
      <WeeklyPlanDrawer
        open
        onClose={() => {}}
        currentPlan={null}
        onTaskClick={() => {}}
        onFinalized={() => {}}
      />
    </MemoryRouter>,
  );
}

describe('WeeklyPlanDrawer start actions', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('offers the start buttons when the backlog holds plannable tasks', async () => {
    fetchPlanningEntry.mockResolvedValue(entry(3));
    renderDrawer();

    const next = await screen.findByRole('button', { name: /Plan next week/ });
    expect(next).toBeEnabled();
    expect(screen.queryByText(/Nothing to plan yet/)).toBeNull();
  });

  it('greys them out and says why when there is nothing plannable', async () => {
    // A brand-new account is exactly this: tutorial cards only, which the planner ignores.
    // Without the hint the disabled button reads as a broken app rather than an empty backlog.
    fetchPlanningEntry.mockResolvedValue(entry(0));
    renderDrawer();

    await waitFor(() => expect(screen.getByText(/Nothing to plan yet/)).toBeInTheDocument());
    for (const name of [/Plan this week/, /Plan next week/]) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
  });
});

const choiceTurn = (options: { id: string; label: string }[]) => ({
  sessionId: 'session-1',
  phase: 'AWAITING_INTERACTIVE_REPLY',
  messages: [{ type: 'choice' as const, text: 'Ready to wrap up?', completions: [], options }],
});

describe('WeeklyPlanDrawer replies', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  async function openChoice() {
    fetchPlanningEntry.mockResolvedValue(entry(3));
    startPlanning.mockResolvedValue(choiceTurn([{ id: 'wrap_up', label: 'Yes, wrap up' }]));
    renderDrawer();
    fireEvent.click(await screen.findByRole('button', { name: /Plan next week/ }));
    return screen.findByRole('button', { name: 'Yes, wrap up' });
  }

  it('sends a chosen option by id alone, not as free text', async () => {
    replyPlanning.mockResolvedValue({ sessionId: 'session-1', phase: 'CONVERSING', messages: [] });

    fireEvent.click(await openChoice());
    await waitFor(() => expect(replyPlanning).toHaveBeenCalled());

    // The label belongs in the transcript, not in the request: the server reads `text` alongside
    // `optionId` as free text the user typed *on top of* their choice.
    expect(replyPlanning).toHaveBeenCalledWith('session-1', { optionId: 'wrap_up' });
    expect(screen.getAllByText('Yes, wrap up').length).toBeGreaterThan(0);
  });

  it('says something when a turn comes back with no messages', async () => {
    replyPlanning.mockResolvedValue({ sessionId: 'session-1', phase: 'CONVERSING', messages: [] });

    fireEvent.click(await openChoice());

    // Otherwise the spinner just stops and the drawer looks idle — the failure mode this guards.
    await waitFor(() => expect(screen.getByText(/No reply came back/)).toBeInTheDocument());
  });
});
