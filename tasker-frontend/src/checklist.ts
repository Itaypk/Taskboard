/**
 * A list item that starts with a GFM task marker. Mirrors marked's own rule (`listIsTask`:
 * `[ ]`/`[x]`, then spaces, then content), so the Nth match here is the Nth checkbox marked renders.
 * The prefix allows blockquote markers and indentation, for quoted and nested lists.
 */
const TASK_ITEM = /^((?:[ \t]*>)*[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)\[([ xX])\](?= +\S)/;
const FENCE = /^[ \t]*(?:>[ \t]*)*(`{3,}|~{3,})/;

/** One checklist item: its line, the matched prefix (bullet and box) and whether it's ticked. */
interface ChecklistItem {
  line: number;
  prefix: string;
  markerLength: number;
  checked: boolean;
}

/** The note's checklist items in document order, skipping fenced code blocks. */
function checklistItems(lines: string[]): ChecklistItem[] {
  const items: ChecklistItem[] = [];
  let fence: string | null = null;
  for (let i = 0; i < lines.length; i++) {
    const fenceMatch = FENCE.exec(lines[i]);
    if (fenceMatch) {
      const marker = fenceMatch[1];
      if (fence === null) fence = marker;
      else if (marker[0] === fence[0] && marker.length >= fence.length) fence = null;
      continue;
    }
    if (fence !== null) continue;
    const item = TASK_ITEM.exec(lines[i]);
    if (item) items.push({ line: i, prefix: item[1], markerLength: item[0].length, checked: item[2] !== ' ' });
  }
  return items;
}

/**
 * Flips the `index`-th (0-based) checklist item in a markdown note, counting in document order and
 * skipping fenced code blocks. Returns the note unchanged when there is no such item.
 */
export function toggleChecklistItem(markdown: string, index: number): string {
  const lines = markdown.split('\n');
  const item = checklistItems(lines)[index];
  if (!item) return markdown;
  const mark = item.checked ? ' ' : 'x';
  lines[item.line] = `${item.prefix}[${mark}]${lines[item.line].slice(item.markerLength)}`;
  return lines.join('\n');
}

export interface ChecklistProgress {
  done: number;
  total: number;
}

/**
 * Counts a note's checklist items and how many are ticked. Every box counts once, nested or not,
 * so the numbers always match the boxes the note renders.
 */
export function checklistProgress(markdown: string): ChecklistProgress {
  const items = checklistItems(markdown.split('\n'));
  return { done: items.filter(i => i.checked).length, total: items.length };
}
