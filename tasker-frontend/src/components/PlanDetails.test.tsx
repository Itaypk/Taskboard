import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CurrentPlan } from '../types';
import { PlanDetails } from './PlanDetails';

const basePlan: CurrentPlan = {
  id: 'plan-1',
  status: 'completed',
  startedAt: '2026-11-01T08:00:00Z',
  endedAt: '2026-11-01T08:20:00Z',
  weekNote: null,
  revisionNotes: [],
  tasks: [],
  weekStart: '2026-11-01',
  weekEnd: '2026-11-07',
};

describe('PlanDetails', () => {
  it('leaves out the notes section when the plan has no notes', () => {
    render(<PlanDetails plan={basePlan} onTaskClick={() => {}} />);

    expect(screen.queryByText('Notes')).not.toBeInTheDocument();
  });

  it('shows the week note followed by each revision note', () => {
    render(
      <PlanDetails
        plan={{
          ...basePlan,
          weekNote: 'Heavy week, travelling on Thursday.',
          revisionNotes: [
            { at: '2026-11-03T10:00:00Z', text: 'Moved the dentist, the clinic rescheduled.' },
            { at: '2026-11-04T10:00:00Z', text: 'Dropped the report, its deadline moved.' },
          ],
        }}
        onTaskClick={() => {}}
      />,
    );

    expect(screen.getByText('Notes')).toBeInTheDocument();
    expect(screen.getByText('Heavy week, travelling on Thursday.')).toBeInTheDocument();
    expect(screen.getByText('Moved the dentist, the clinic rescheduled.')).toBeInTheDocument();
    expect(screen.getByText('Dropped the report, its deadline moved.')).toBeInTheDocument();
    expect(screen.getAllByText(/^Revised /)).toHaveLength(2);
  });
});
