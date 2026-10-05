import { describe, it, expect } from 'vitest';
import { notePreview } from './utils';

describe('notePreview', () => {
  it('collapses a multi-line markdown note to one plain line', () => {
    const md = 'Cover the migration plan.\n\n- Who can invite whom\n- What happens on leave';
    expect(notePreview(md)).toBe('Cover the migration plan. Who can invite whom What happens on leave');
  });

  it('strips heading, emphasis, and inline-code markers', () => {
    expect(notePreview('### Options\n\nUse the **paper** pill `(chosen)`'))
      .toBe('Options Use the paper pill (chosen)');
  });

  it('keeps link text but drops the URL', () => {
    expect(notePreview('See [the paper](https://example.com/x) for details'))
      .toBe('See the paper for details');
  });

  it('drops fenced code blocks entirely', () => {
    expect(notePreview('Before\n\n```\ncode here\n```\n\nAfter')).toBe('Before After');
  });

  it('drops checklist boxes along with their bullets', () => {
    expect(notePreview('- [ ] milk\n- [x] eggs')).toBe('milk eggs');
  });
});
