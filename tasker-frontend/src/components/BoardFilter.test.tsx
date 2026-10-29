import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BoardFilter } from './BoardFilter';

describe('BoardFilter', () => {
  it('disables the plan chip while there is no plan yet', () => {
    render(<BoardFilter value="todo" onChange={() => {}} hasCurrentPlan={false} showPlan />);
    expect(screen.getByRole('radio', { name: /This week's plan/ })).toBeDisabled();
  });

  it('hides the plan chip when planning is not offered', () => {
    render(<BoardFilter value="todo" onChange={() => {}} hasCurrentPlan={false} showPlan={false} />);
    expect(screen.queryByRole('radio', { name: /This week's plan/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(3);
  });
});
