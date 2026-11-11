import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { WeeklyPlanDrawer } from './WeeklyPlanDrawer';

// The drawer fetches its overview on open; stub the two calls it makes there. `plannableTaskCount`
// is the field under test — it decides whether the two "Plan …" buttons are offered at all.
const fetchPlanningEntry = vi.fn();
const startPlanning = vi.fn();
const replyPlanning = vi.fn();
const startQuickAdd = vi.fn();
const replyQuickAdd = vi.fn();
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
  startQuickAdd: (...args: unknown[]) => startQuickAdd(...args),
  replyQuickAdd: (...args: unknown[]) => replyQuickAdd(...args),
  abandonQuickAdd: vi.fn(),
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
function renderDrawer(props: { boardId?: string; onFinalized?: () => void } = {}) {
  return render(
    <MemoryRouter>
      <WeeklyPlanDrawer
        open
        onClose={() => {}}
        boardId={props.boardId}
        currentPlan={null}
        onTaskClick={() => {}}
        onFinalized={props.onFinalized ?? (() => {})}
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

describe('WeeklyPlanDrawer empty-backlog capture', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  const draftCard = {
    active: true,
    boardChanged: false,
    messages: [{
      type: 'choice' as const,
      text: '**Dentist**',
      completions: [],
      options: [{ id: 'quickadd_save', label: 'Save' }, { id: 'quickadd_cancel', label: 'Cancel' }],
    }],
  };

  async function typeWeek(text: string) {
    fireEvent.change(await screen.findByLabelText(/on your plate/), { target: { value: text } });
    fireEvent.click(screen.getByRole('button', { name: 'Turn into tasks' }));
  }

  it('takes the week as text instead of a dead end when nothing is plannable', async () => {
    fetchPlanningEntry.mockResolvedValue(entry(0));
    startQuickAdd.mockResolvedValue(draftCard);
    renderDrawer({ boardId: 'board-1' });

    await typeWeek('  dentist thursday ');

    expect(startQuickAdd).toHaveBeenCalledWith('board-1', 'dentist thursday');
    // The draft is shown for review, not saved behind the user's back.
    expect(await screen.findByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  it('refreshes the board on save and returns to the overview without starting a session', async () => {
    const onFinalized = vi.fn();
    fetchPlanningEntry.mockResolvedValueOnce(entry(0)).mockResolvedValue(entry(1));
    startQuickAdd.mockResolvedValue(draftCard);
    replyQuickAdd.mockResolvedValue({
      active: false,
      boardChanged: true,
      messages: [{ type: 'text', text: 'Added: Dentist', completions: [], options: [] }],
    });
    renderDrawer({ boardId: 'board-1', onFinalized });

    await typeWeek('dentist thursday');
    fireEvent.click(await screen.findByRole('button', { name: 'Save' }));

    await waitFor(() => expect(onFinalized).toHaveBeenCalled());
    expect(replyQuickAdd).toHaveBeenCalledWith('board-1', { optionId: 'quickadd_save' });

    // Planning is the user's call: the overview now offers the start buttons, nothing more.
    // Two buttons carry that name once the capture is over: the header's back arrow and the done bar's.
    fireEvent.click((await screen.findAllByRole('button', { name: 'Back to plan' })).at(-1)!);
    expect(await screen.findByRole('button', { name: /Plan next week/ })).toBeEnabled();
    expect(startPlanning).not.toHaveBeenCalled();
  });

  it('is not offered when AI is unavailable', async () => {
    fetchPlanningEntry.mockResolvedValue({ ...entry(0), aiAvailable: false });
    renderDrawer({ boardId: 'board-1' });

    await screen.findByRole('button', { name: /Plan next week/ });
    expect(screen.queryByLabelText(/on your plate/)).toBeNull();
  });
});
