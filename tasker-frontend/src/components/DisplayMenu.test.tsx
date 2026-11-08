import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DisplayMenu } from './DisplayMenu';

function renderMenu(overrides: Partial<React.ComponentProps<typeof DisplayMenu>> = {}) {
  const props = {
    viewMode: 'board' as const,
    onViewModeChange: vi.fn(),
    textSize: 'default' as const,
    onTextSizeChange: vi.fn(),
    ...overrides,
  };
  render(<DisplayMenu {...props} />);
  return props;
}

describe('DisplayMenu', () => {
  it('applies a choice without closing, so the board can be watched changing', () => {
    const props = renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Display options' }));

    fireEvent.click(screen.getByRole('radio', { name: 'Larger text' }));
    fireEvent.click(screen.getByRole('radio', { name: /Compact/ }));

    expect(props.onTextSizeChange).toHaveBeenCalledWith('larger');
    expect(props.onViewModeChange).toHaveBeenCalledWith('compact');
    expect(screen.getByRole('group', { name: 'Display options' })).toBeInTheDocument();
  });

  it('marks the current choices as checked', () => {
    renderMenu({ viewMode: 'compact', textSize: 'large' });
    fireEvent.click(screen.getByRole('button', { name: 'Display options' }));

    expect(screen.getByRole('radio', { name: 'Large text' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Default text size' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('radio', { name: /Compact/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('closes on Escape and returns focus to the button', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: 'Display options' });
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('group', { name: 'Display options' })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
