/**
 * A list item that starts with a GFM task marker. Mirrors marked's own rule (`listIsTask`:
 * `[ ]`/`[x]`, then spaces, then content), so the Nth match here is the Nth checkbox marked renders.
 * The prefix allows blockquote markers and indentation, for quoted and nested lists.
 */
const TASK_ITEM = /^((?:[ \t]*>)*[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)\[([ xX])\](?= +\S)/;
const FENCE = /^[ \t]*(?:>[ \t]*)*(`{3,}|~{3,})/;

/**
 * Flips the `index`-th (0-based) checklist item in a markdown note, counting in document order and
 * skipping fenced code blocks. Returns the note unchanged when there is no such item.
 */
export function toggleChecklistItem(markdown: string, index: number): string {
  const lines = markdown.split('\n');
  let fence: string | null = null;
  let seen = 0;
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
    if (!item) continue;
    if (seen++ === index) {
      const mark = item[2] === ' ' ? 'x' : ' ';
      lines[i] = `${item[1]}[${mark}]${lines[i].slice(item[0].length)}`;
      return lines.join('\n');
    }
  }
  return markdown;
}
