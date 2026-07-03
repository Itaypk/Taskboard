import type { ReactNode } from 'react';

// Lucide (ISC) icon paths, inlined for a consistent stroke-based toolbar. camelCase SVG attrs
// so React doesn't warn (the app has legacy kebab-case SVGs that log stroke-width errors).
function Svg({ children }: { children: ReactNode }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export const BoldIcon = () => (
  <Svg><path d="M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8" /></Svg>
);

export const ItalicIcon = () => (
  <Svg>
    <line x1="19" x2="10" y1="4" y2="4" />
    <line x1="14" x2="5" y1="20" y2="20" />
    <line x1="15" x2="9" y1="4" y2="20" />
  </Svg>
);

export const BulletListIcon = () => (
  <Svg>
    <path d="M3 5h.01" />
    <path d="M3 12h.01" />
    <path d="M3 19h.01" />
    <path d="M8 5h13" />
    <path d="M8 12h13" />
    <path d="M8 19h13" />
  </Svg>
);

export const OrderedListIcon = () => (
  <Svg>
    <path d="M11 5h10" />
    <path d="M11 12h10" />
    <path d="M11 19h10" />
    <path d="M4 4h1v5" />
    <path d="M4 9h2" />
    <path d="M6.5 20H3.4c0-1 2.6-1.925 2.6-3.5a1.5 1.5 0 0 0-2.6-1.02" />
  </Svg>
);

export const ChecklistIcon = () => (
  <Svg>
    <path d="M13 5h8" />
    <path d="M13 12h8" />
    <path d="M13 19h8" />
    <path d="m3 17 2 2 4-4" />
    <rect x="3" y="4" width="6" height="6" rx="1" />
  </Svg>
);

export const IndentIcon = () => (
  <Svg>
    <path d="M17 12H3" />
    <path d="m11 18 6-6-6-6" />
    <path d="M21 5v14" />
  </Svg>
);

export const OutdentIcon = () => (
  <Svg>
    <path d="M3 19V5" />
    <path d="m13 6-6 6 6 6" />
    <path d="M7 12h14" />
  </Svg>
);

export const LinkIcon = () => (
  <Svg>
    <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
    <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
  </Svg>
);

/** Code brackets: shown in WYSIWYG mode — click to drop to raw Markdown. */
export const MarkdownIcon = () => (
  <Svg>
    <path d="m16 18 6-6-6-6" />
    <path d="m8 6-6 6 6 6" />
  </Svg>
);

/** Text cursor "T": shown in raw mode — click to return to the rich editor. */
export const RichTextIcon = () => (
  <Svg>
    <path d="M12 4v16" />
    <path d="M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2" />
    <path d="M9 20h6" />
  </Svg>
);
