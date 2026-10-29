import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SubtaskCount, SubtaskStroke } from './SubtaskProgress';

describe('SubtaskCount', () => {
  it('shows the fraction and names it in full for screen readers', () => {
    render(<SubtaskCount progress={{ done: 2, total: 4 }} />);
    expect(screen.getByText('2/4')).toBeInTheDocument();
    expect(screen.getByText('Sub-tasks done: 2 of 4')).toBeInTheDocument();
  });
});

describe('SubtaskStroke', () => {
  it('draws the swipe up to the share that is done', () => {
    const { container } = render(<SubtaskStroke progress={{ done: 3, total: 4 }} variant="row" />);
    const swipe = container.querySelector('span > span') as HTMLElement;
    expect(swipe.style.width).toBe('calc(75% + 3px)');
  });

  it('draws only the guide line when nothing is done', () => {
    const { container } = render(<SubtaskStroke progress={{ done: 0, total: 4 }} variant="row" />);
    expect(container.querySelector('span > span')).toBeNull();
  });
});
