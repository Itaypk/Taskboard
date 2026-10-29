import { describe, expect, it } from 'vitest';
import { checklistProgress, toggleChecklistItem } from './checklist';

describe('toggleChecklistItem', () => {
  it('checks and unchecks the item at the given index', () => {
    const note = '- [ ] milk\n- [x] eggs\n- [ ] bread';
    expect(toggleChecklistItem(note, 0)).toBe('- [x] milk\n- [x] eggs\n- [ ] bread');
    expect(toggleChecklistItem(note, 1)).toBe('- [ ] milk\n- [ ] eggs\n- [ ] bread');
    expect(toggleChecklistItem(note, 2)).toBe('- [ ] milk\n- [x] eggs\n- [x] bread');
  });

  it('counts ordered, nested and quoted items in document order', () => {
    const note = 'Intro\n\n1. [ ] one\n  * [ ] nested\n> - [ ] quoted';
    expect(toggleChecklistItem(note, 1)).toBe('Intro\n\n1. [ ] one\n  * [x] nested\n> - [ ] quoted');
    expect(toggleChecklistItem(note, 2)).toBe('Intro\n\n1. [ ] one\n  * [ ] nested\n> - [x] quoted');
  });

  it('treats an uppercase X as checked', () => {
    expect(toggleChecklistItem('- [X] done', 0)).toBe('- [ ] done');
  });

  it('skips markers inside fenced code blocks', () => {
    const note = '```\n- [ ] not a task\n```\n~~~~\n- [ ] nor this\n~~~\n~~~~\n- [ ] real';
    expect(toggleChecklistItem(note, 0)).toBe('```\n- [ ] not a task\n```\n~~~~\n- [ ] nor this\n~~~\n~~~~\n- [x] real');
  });

  it('ignores markers that do not render as checkboxes', () => {
    const note = '[ ] no bullet\n- [ ]\n- [] squashed\n- [ ] real';
    expect(toggleChecklistItem(note, 0)).toBe('[ ] no bullet\n- [ ]\n- [] squashed\n- [x] real');
  });

  it('leaves the note alone when the index is out of range', () => {
    expect(toggleChecklistItem('- [ ] only', 3)).toBe('- [ ] only');
  });
});

describe('checklistProgress', () => {
  it('counts every box once, nested or not', () => {
    const note = 'Intro\n\n- [x] date\n- [x] venue\n- [ ] logistics\n  - [x] book';
    expect(checklistProgress(note)).toEqual({ done: 3, total: 4 });
  });

  it('counts ordered and quoted items, and an uppercase X', () => {
    expect(checklistProgress('1. [X] one\n> - [ ] quoted')).toEqual({ done: 1, total: 2 });
  });

  it('skips fenced code and markers that do not render as checkboxes', () => {
    const note = '```\n- [x] not a task\n```\n[ ] no bullet\n- [ ]\n- [x] real';
    expect(checklistProgress(note)).toEqual({ done: 1, total: 1 });
  });

  it('is empty for a note without a checklist', () => {
    expect(checklistProgress('Just prose.\n\n- a plain bullet')).toEqual({ done: 0, total: 0 });
  });
});
