import { useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { marked } from 'marked';
import markedBidi from 'marked-bidi';
import DOMPurify from 'dompurify';

marked.use(markedBidi());

// Open note links in a new tab (like the dedicated URL field), safely. Runs as the last
// per-node step, so target/rel survive sanitization without widening ALLOWED_ATTR.
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

interface MarkdownRendererProps {
    content: string;
    maxLength?: number;
    showExpandButton?: boolean;
}

function truncateAtWord(text: string, maxLen: number): string {
    if (text.length <= maxLen) return text;
    const slice = text.substring(0, maxLen);
    const lastPunct = Math.max(slice.lastIndexOf('.'), slice.lastIndexOf('!'), slice.lastIndexOf('?'));
    const lastSpace = slice.lastIndexOf(' ');
    const breakPoint = lastPunct > lastSpace ? lastPunct + 1 : lastSpace > 0 ? lastSpace : maxLen;
    return slice.substring(0, breakPoint) + '…';
}

function renderMarkdown(markdown: string): string {
    try {
        const html = marked.parse(markdown) as string;
        // DOMPurify sanitizes the marked output to prevent XSS
        return DOMPurify.sanitize(html, {
            ALLOWED_TAGS: ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'br', 'strong', 'em', 'b', 'i', 'u',
                           'code', 'pre', 'ul', 'ol', 'li', 'blockquote', 'a', 'img',
                           'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'input'],
            ALLOWED_ATTR: ['href', 'title', 'src', 'alt', 'align', 'dir', 'type', 'checked', 'disabled'],
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

function stopEventIfLink(e: SyntheticEvent) {
    if ((e.target as HTMLElement).closest('a')) e.stopPropagation();
}

function MarkdownRenderer({ content, maxLength, showExpandButton = true }: MarkdownRendererProps) {
    const { t } = useTranslation();
    const [isExpanded, setIsExpanded] = useState(false);

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
                onClick={stopEventIfLink}
                dangerouslySetInnerHTML={{ __html: renderMarkdown(displayContent) }}
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
