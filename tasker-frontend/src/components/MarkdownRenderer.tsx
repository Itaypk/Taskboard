import { useState, type KeyboardEvent, type MouseEvent, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { marked } from 'marked';
import markedBidi from 'marked-bidi';
import DOMPurify from 'dompurify';

marked.use(markedBidi());

/** Root-relative hrefs (`/faq`) are our own SPA routes; everything else leaves the app. */
function isInternalHref(href: string | null): boolean {
  return href !== null && href.startsWith('/') && !href.startsWith('//');
}

// Open note links in a new tab (like the dedicated URL field), safely. Runs as the last
// per-node step, so target/rel survive sanitization without widening ALLOWED_ATTR.
// In-app links are left alone so they can be handled by the router instead.
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A' && !isInternalHref(node.getAttribute('href'))) {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

interface MarkdownRendererProps {
    content: string;
    maxLength?: number;
    showExpandButton?: boolean;
    /**
     * Makes checklist checkboxes clickable; called with the clicked box's 0-based index in document
     * order (see `toggleChecklistItem`). Without it, checkboxes render read-only.
     */
    onToggleCheckbox?: (index: number) => void;
}

function truncateAtWord(text: string, maxLen: number): string {
    if (text.length <= maxLen) return text;
    const slice = text.substring(0, maxLen);
    const lastPunct = Math.max(slice.lastIndexOf('.'), slice.lastIndexOf('!'), slice.lastIndexOf('?'));
    const lastSpace = slice.lastIndexOf(' ');
    const breakPoint = lastPunct > lastSpace ? lastPunct + 1 : lastSpace > 0 ? lastSpace : maxLen;
    return slice.substring(0, breakPoint) + '…';
}

const ALLOWED_ATTR = ['href', 'title', 'src', 'alt', 'align', 'dir', 'type', 'checked', 'disabled'];
// marked renders checklist boxes as `disabled`; dropping that attribute is what makes them clickable.
const INTERACTIVE_ALLOWED_ATTR = ALLOWED_ATTR.filter(attr => attr !== 'disabled');

function renderMarkdown(markdown: string, interactive = false): string {
    try {
        const html = marked.parse(markdown) as string;
        // DOMPurify sanitizes the marked output to prevent XSS
        return DOMPurify.sanitize(html, {
            ALLOWED_TAGS: ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'br', 'strong', 'em', 'b', 'i', 'u',
                           'code', 'pre', 'ul', 'ol', 'li', 'blockquote', 'a', 'img',
                           'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'input'],
            ALLOWED_ATTR: interactive ? INTERACTIVE_ALLOWED_ATTR : ALLOWED_ATTR,
        });
    } catch (error) {
        console.error('Error rendering markdown:', error);
        return markdown
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/\n/g, '<br>');
    }
}

function isCheckbox(target: EventTarget): target is HTMLInputElement {
    return target instanceof HTMLInputElement && target.type === 'checkbox';
}

function stopEventIfLink(e: SyntheticEvent) {
    if ((e.target as HTMLElement).closest('a') || isCheckbox(e.target)) e.stopPropagation();
}

function MarkdownRenderer({ content, maxLength, showExpandButton = true, onToggleCheckbox }: MarkdownRendererProps) {
    const { t } = useTranslation();
    const navigate = useNavigate();
    const [isExpanded, setIsExpanded] = useState(false);

    // Route in-app links through the router; a plain <a> would trigger a full page reload.
    const handleClick = (e: MouseEvent) => {
        stopEventIfLink(e);
        if (isCheckbox(e.target)) {
            // The note's markdown is the source of truth: cancel the native toggle and let the
            // re-render with the updated content flip the box.
            e.preventDefault();
            if (onToggleCheckbox) {
                const boxes = Array.from(e.currentTarget.querySelectorAll('input[type="checkbox"]'));
                onToggleCheckbox(boxes.indexOf(e.target));
            }
            return;
        }
        const anchor = (e.target as HTMLElement).closest('a');
        const href = anchor?.getAttribute('href') ?? null;
        if (!isInternalHref(href) || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        navigate(href!);
    };

    if (!content) return <div className="markdown-content" />;

    const needsTruncation = maxLength !== undefined && content.length > maxLength;
    const displayContent = needsTruncation && !isExpanded ? truncateAtWord(content, maxLength) : content;

    return (
        <div className="markdown-renderer-wrapper">
            <div
                className="markdown-content"
                // A note usually renders inside a clickable, drag-enabled card. Mirror the URL-field
                // link button (PostItNote): swallow pointerdown + click when they originate inside a
                // link, so following it neither starts a drag nor fires the card's open handler.
                onPointerDown={stopEventIfLink}
                onClick={handleClick}
                // Space on a focused checkbox must toggle it, not open or drag the surrounding card.
                onKeyDown={(e: KeyboardEvent) => { if (isCheckbox(e.target)) e.stopPropagation(); }}
                dangerouslySetInnerHTML={{ __html: renderMarkdown(displayContent, onToggleCheckbox !== undefined) }}
            />
            {needsTruncation && showExpandButton && (
                <button
                    type="button"
                    className="btn-expand-text"
                    onClick={() => setIsExpanded(!isExpanded)}
                    aria-label={isExpanded ? t('markdownRenderer.showLess') : t('markdownRenderer.showMore')}
                >
                    {isExpanded ? `▲ ${t('markdownRenderer.showLess')}` : `▼ ${t('markdownRenderer.showMore')}`}
                </button>
            )}
        </div>
    );
}

export { MarkdownRenderer };
export default MarkdownRenderer;
