import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Extension } from '@tiptap/core';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { Placeholder } from '@tiptap/extensions';
import { TaskList } from '@tiptap/extension-task-list';
import { TaskItem } from '@tiptap/extension-task-item';
import { Table, TableRow, TableCell, TableHeader } from '@tiptap/extension-table';
import {
  BoldIcon, ItalicIcon, BulletListIcon, OrderedListIcon, ChecklistIcon,
  IndentIcon, OutdentIcon, LinkIcon, MarkdownIcon, RichTextIcon,
} from './NoteEditorIcons';
import styles from './NoteEditor.module.css';

// Mirrors the backend @field:Size cap on the task description.
const MAX_LENGTH = 5000;

// Render every block with dir="auto" so the browser picks LTR/RTL per block from its first
// strong character — no button needed. `dir` is a DOM-only attribute; the Markdown serializer
// ignores it, so it never leaks into stored content.
const AutoTextDirection = Extension.create({
  name: 'autoTextDirection',
  addGlobalAttributes() {
    return [
      {
        types: [
          'paragraph', 'heading', 'blockquote',
          'bulletList', 'orderedList', 'listItem', 'taskList', 'taskItem',
          'tableHeader', 'tableCell',
        ],
        attributes: {
          dir: {
            default: 'auto',
            renderHTML: attrs => ({ dir: attrs.dir ?? 'auto' }),
            parseHTML: el => el.getAttribute('dir') || 'auto',
          },
        },
      },
    ];
  },
});

interface NoteEditorProps {
  value: string;
  onChange: (markdown: string) => void;
  placeholder?: string;
  error?: boolean;
  disabled?: boolean;
}

/**
 * WYSIWYG note editor whose source of truth is a Markdown string, so it drops into the
 * existing `description` flow with no storage change. A "raw" toggle exposes the underlying
 * Markdown for power users and as an escape hatch if the WYSIWYG ever mangles a round-trip.
 */
function NoteEditor({ value, onChange, placeholder, error, disabled }: NoteEditorProps) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<'rich' | 'raw'>('rich');

  // Keep the latest onChange/value without re-creating the editor (useEditor is init-once).
  const onChangeRef = useRef(onChange);
  const valueRef = useRef(value);
  useEffect(() => {
    onChangeRef.current = onChange;
    valueRef.current = value;
  });

  const editor = useEditor({
    extensions: [
      StarterKit.configure({ link: { openOnClick: false } }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Table.configure({ resizable: false }),
      TableRow,
      TableHeader,
      TableCell,
      Placeholder.configure({ placeholder: placeholder ?? '' }),
      AutoTextDirection,
      Markdown,
    ],
    editable: !disabled,
    content: '',
    editorProps: { attributes: { class: styles.content } },
    // Load initial Markdown here, not in an effect: onCreate runs on a fully-constructed,
    // live editor, so it can't hit a torn-down instance (React StrictMode double-mounts the
    // editor, and touching a destroyed one throws "commandManager is null").
    onCreate: ({ editor }) => {
      if (valueRef.current) {
        editor.commands.setContent(valueRef.current, { contentType: 'markdown', emitUpdate: false });
      }
    },
    onUpdate: ({ editor }) => onChangeRef.current(editor.getMarkdown()),
  });

  // Sync later external Markdown changes into the editor (raw→rich toggle, switching tasks).
  // Skipped on our own keystrokes (value already equals getMarkdown) and guarded against a
  // destroyed editor from a StrictMode/unmount race.
  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    if (value !== editor.getMarkdown()) {
      editor.commands.setContent(value, { contentType: 'markdown', emitUpdate: false });
    }
  }, [editor, value]);

  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    editor.setEditable(!disabled);
  }, [editor, disabled]);

  const overLimit = value.length > MAX_LENGTH;

  function toggleLink() {
    if (!editor) return;
    if (editor.isActive('link')) {
      editor.chain().focus().unsetLink().run();
      return;
    }
    const url = window.prompt(t('noteEditor.linkUrlPrompt'))?.trim();
    if (url) editor.chain().focus().setLink({ href: url }).run();
  }

  function indent() {
    if (!editor) return;
    if (!editor.chain().focus().sinkListItem('listItem').run()) {
      editor.chain().focus().sinkListItem('taskItem').run();
    }
  }

  function outdent() {
    if (!editor) return;
    if (!editor.chain().focus().liftListItem('listItem').run()) {
      editor.chain().focus().liftListItem('taskItem').run();
    }
  }

  const wrapperClass = [styles.wrapper, error ? styles.wrapperError : '', disabled ? styles.wrapperDisabled : '']
    .filter(Boolean)
    .join(' ');

  return (
    <div className={wrapperClass}>
      <div className={styles.toolbar} role="toolbar" aria-label={t('noteEditor.formatting')}>
        {mode === 'rich' && editor && (
          <>
            <ToolbarButton label={t('noteEditor.bold')} active={editor.isActive('bold')} disabled={disabled}
              onClick={() => editor.chain().focus().toggleBold().run()}><BoldIcon /></ToolbarButton>
            <ToolbarButton label={t('noteEditor.italic')} active={editor.isActive('italic')} disabled={disabled}
              onClick={() => editor.chain().focus().toggleItalic().run()}><ItalicIcon /></ToolbarButton>
            <span className={styles.divider} aria-hidden="true" />
            <ToolbarButton label={t('noteEditor.bulletedList')} active={editor.isActive('bulletList')} disabled={disabled}
              onClick={() => editor.chain().focus().toggleBulletList().run()}><BulletListIcon /></ToolbarButton>
            <ToolbarButton label={t('noteEditor.numberedList')} active={editor.isActive('orderedList')} disabled={disabled}
              onClick={() => editor.chain().focus().toggleOrderedList().run()}><OrderedListIcon /></ToolbarButton>
            <ToolbarButton label={t('noteEditor.checklist')} active={editor.isActive('taskList')} disabled={disabled}
              onClick={() => editor.chain().focus().toggleTaskList().run()}><ChecklistIcon /></ToolbarButton>
            <ToolbarButton label={t('noteEditor.indent')} disabled={disabled} onClick={indent}><IndentIcon /></ToolbarButton>
            <ToolbarButton label={t('noteEditor.outdent')} disabled={disabled} onClick={outdent}><OutdentIcon /></ToolbarButton>
            <span className={styles.divider} aria-hidden="true" />
            <ToolbarButton label={t('noteEditor.link')} active={editor.isActive('link')} disabled={disabled}
              onClick={toggleLink}><LinkIcon /></ToolbarButton>
          </>
        )}
        <button
          type="button"
          className={`${styles.tbtn} ${styles.tbtnRight}`}
          onClick={() => setMode(m => (m === 'rich' ? 'raw' : 'rich'))}
          aria-pressed={mode === 'raw'}
          aria-label={mode === 'rich' ? t('noteEditor.editAsMarkdown') : t('noteEditor.richEditor')}
          title={mode === 'rich' ? t('noteEditor.editAsMarkdown') : t('noteEditor.richEditor')}
        >
          {mode === 'rich' ? <MarkdownIcon /> : <RichTextIcon />}
        </button>
      </div>

      {mode === 'rich' ? (
        <EditorContent editor={editor} className={styles.editor} />
      ) : (
        <textarea
          className={styles.raw}
          value={value}
          onChange={e => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          rows={6}
        />
      )}

      {overLimit && (
        <p className={styles.counter}>{value.length}/{MAX_LENGTH}</p>
      )}
    </div>
  );
}

interface ToolbarButtonProps {
  label: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}

function ToolbarButton({ label, active, disabled, onClick, children }: ToolbarButtonProps) {
  return (
    <button
      type="button"
      className={`${styles.tbtn}${active ? ' ' + styles.tbtnActive : ''}`}
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      aria-pressed={active}
      title={label}
    >
      {children}
    </button>
  );
}

export { NoteEditor };
export default NoteEditor;
