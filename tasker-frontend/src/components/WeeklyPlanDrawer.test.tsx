import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { WeeklyPlanDrawer } from './WeeklyPlanDrawer';

// The drawer fetches its overview on open; stub the two calls it makes there. `plannableTaskCount`
// is the field under test — it decides whether the two "Plan …" buttons are offered at all.
const fetchPlanningEntry = vi.fn();
vi.mock('../api', () => ({
  ApiError: class extends Error {},
  fetchPlanningEntry: () => fetchPlanningEntry(),
  fetchPlans: () => Promise.resolve([]),
  fetchPlanningTranscript: () => Promise.resolve(null),
  fetchPlanForWeek: () => Promise.resolve(null),
  fetchEventsForWeek: () => Promise.resolve([]),
  startPlanning: vi.fn(),
  replyPlanning: vi.fn(),
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

function renderDrawer() {
  return render(
    <WeeklyPlanDrawer
      open
      onClose={() => {}}
      currentPlan={null}
      onTaskClick={() => {}}
      onFinalized={() => {}}
    />,
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
