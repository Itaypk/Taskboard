import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './SettingsModal.module.css';
import type { UserSettings, SettingsOptions } from '../types';
import { useAuth } from '../auth/AuthContext';
import { fetchIdentities } from '../auth/authApi';
import { fetchSettingsOptions } from '../api';
import { ApiTokens } from './ApiTokens';
import { InboundMailAddress } from './InboundMailAddress';
import { MessagingApps } from './MessagingApps';
import { CalendarDeliverySection } from './CalendarDeliverySection';
import { applyLocale } from '../i18n';
import { usePublicConfig } from '../publicConfig';
import { SETTINGS_SECTIONS, type SettingsSection } from '../taskLink';
import { useSettingsAutosave, settingsPayload, type SaveStatus } from './settings/useSettingsAutosave';
import { GeneralSection } from './settings/GeneralSection';
import { SignInSection } from './settings/SignInSection';
import { PlanningSection } from './settings/PlanningSection';
import { RemindersSection } from './settings/RemindersSection';
import { AiSection } from './settings/AiSection';
import { DataSection } from './settings/DataSection';
import { SectionIcon } from './settings/SectionIcon';
import { sectionSummary } from './settings/sectionSummary';

interface SettingsModalProps {
  settings: UserSettings;
  open: boolean;
  /** The section from the `/settings/<section>` route; null for bare `/settings`. */
  section: SettingsSection | null;
  /** Moves to a section (null: the section list, on a phone). The route is the source of truth. */
  onNavigate: (section: SettingsSection | null) => void;
  onClose: () => void;
  /** Hears every saved change, so the rest of the app stays in step without a refetch. */
  onSave: (patch: Partial<UserSettings>) => void;
  onAccountDeleted: () => void;
}

/** Matches the stylesheet's phone breakpoint: below it, the list and a section take turns. */
const PHONE_QUERY = '(max-width: 600px)';

function subscribePhone(onChange: () => void) {
  const mq = window.matchMedia?.(PHONE_QUERY);
  mq?.addEventListener('change', onChange);
  return () => mq?.removeEventListener('change', onChange);
}
const isPhone = () => window.matchMedia?.(PHONE_QUERY).matches ?? false;

/**
 * Settings, as a list of sections. On a wider screen the list is a rail beside the open section;
 * on a phone the list comes first, with a line under each section saying what's set there, and
 * a section opens in its place. Everything saves as it changes; there is no Save button.
 */
export function SettingsModal(props: SettingsModalProps) {
  const { open, onClose } = props;
  // Each opening starts from the current settings: remounting the dialog (a new key on the
  // closed→open edge) resets the form, the save state and every section's local state at once,
  // while the overlay stays mounted so the open/close transition still plays.
  const [opening, setOpening] = useState(open ? 1 : 0);
  const [wasOpen, setWasOpen] = useState(open);
  if (open && !wasOpen) {
    setWasOpen(true);
    setOpening(n => n + 1);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  }

  return (
    <div
      className={`modal-overlay modal-overlay--top${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      {opening > 0 && <SettingsDialog key={opening} {...props} />}
    </div>
  );
}

function SettingsDialog({ settings, open, section, onNavigate, onClose, onSave, onAccountDeleted }: SettingsModalProps) {
  const { t, i18n } = useTranslation();
  const { inboundMail: inboundMailEnabled, ai: instanceAi } = usePublicConfig();
  const { state: authState } = useAuth();
  // Telegram-delivered features need a chat the user has opened, not just a linked account.
  const telegramChatReady = authState.status === 'authenticated' && authState.user.telegramChatReady;
  const phone = useSyncExternalStore(subscribePhone, isPhone);

  const [options, setOptions] = useState<SettingsOptions | null>(null);
  const [providers, setProviders] = useState<string[] | null>(null);
  const [calendarDelivery, setCalendarDelivery] = useState(settings.calendarDelivery);
  const [deleteConfirm, setDeleteConfirm] = useState(false);

  const handleSaved = useCallback((saved: UserSettings, previous: UserSettings) => {
    onSave(settingsPayload(saved));
    // Apply a language change right away (UI language, document lang/dir, Intl locale).
    if (saved.preferredLanguage !== previous.preferredLanguage) void applyLocale(saved.preferredLanguage);
  }, [onSave]);
  const autosave = useSettingsAutosave(settings, handleSaved);
  const { form, save, status } = autosave;

  // Planning and the AI opt-out only mean something on an instance with AI.
  const sections = SETTINGS_SECTIONS.filter(s => instanceAi || (s !== 'planning' && s !== 'ai'));
  const requested = section && sections.includes(section) ? section : null;
  const active: SettingsSection | null = requested ?? (phone ? null : 'general');

  useEffect(() => {
    let cancelled = false;
    fetchSettingsOptions()
      .then(o => { if (!cancelled) setOptions(o); })
      .catch(console.error);
    return () => { cancelled = true; };
  }, []);

  // The sign-in summary lists the linked methods; reloaded each time the list is shown, since
  // they can change inside the sign-in section.
  const showingList = active === null;
  useEffect(() => {
    if (!showingList) return;
    let cancelled = false;
    fetchIdentities()
      .then(ids => { if (!cancelled) setProviders(ids.map(i => i.provider)); })
      .catch(e => console.error('Failed to load connected accounts', e));
    return () => { cancelled = true; };
  }, [showingList]);

  const close = useCallback(() => {
    // A focused text field hasn't blurred yet when Escape closes the dialog.
    void save();
    onClose();
  }, [save, onClose]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (deleteConfirm) { setDeleteConfirm(false); return; }
      close();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, close, deleteConfirm]);

  const handleDeliveryChange = useCallback((delivery: UserSettings['calendarDelivery']) => {
    setCalendarDelivery(delivery);
    onSave({ calendarDelivery: delivery });
  }, [onSave]);

  const sectionTitle = (s: SettingsSection) => t(`settingsModal.sections.${s}`);

  const renderSection = (s: SettingsSection) => {
    switch (s) {
      case 'general':
        return <GeneralSection autosave={autosave} options={options} />;
      case 'signin':
        return <SignInSection settings={settings} />;
      case 'planning':
        return (
          <PlanningSection
            autosave={autosave}
            options={options}
            telegramChatReady={telegramChatReady}
            onOpenAi={() => onNavigate('ai')}
          />
        );
      case 'reminders':
        return <RemindersSection autosave={autosave} instanceAi={instanceAi} telegramChatReady={telegramChatReady} />;
      case 'calendar':
        return <CalendarDeliverySection onVerifyEmail={() => onNavigate('signin')} onDeliveryChange={handleDeliveryChange} />;
      case 'ai':
        return <AiSection autosave={autosave} />;
      case 'connections':
        return (
          <>
            {inboundMailEnabled && <InboundMailAddress />}
            <MessagingApps />
            <ApiTokens />
          </>
        );
      case 'data':
        return (
          <DataSection
            onAccountDeleted={onAccountDeleted}
            deleteConfirm={deleteConfirm}
            onDeleteConfirmChange={setDeleteConfirm}
          />
        );
    }
  };

  const closeButton = (
    <button className="drawer__close" onClick={close} aria-label={t('settingsModal.close')}>×</button>
  );

  if (phone && active === null) {
    return (
      <div className={`modal ${styles.modal}`} role="dialog" aria-modal="true" aria-label={t('settingsModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{t('settingsModal.title')}</span>
          {closeButton}
        </div>
        <nav className={`modal__body ${styles.list}`} aria-label={t('settingsModal.title')}>
          {sections.map(s => (
            <button key={s} type="button" className={styles.listItem} onClick={() => onNavigate(s)}>
              <SectionIcon section={s} className={styles.listIcon} />
              <span className={styles.listText}>
                <span className={styles.listTitle}>{sectionTitle(s)}</span>
                <span className={styles.listSummary}>
                  {sectionSummary(s, form, {
                    t, language: i18n.language, options, providers, calendarDelivery, inboundMail: inboundMailEnabled,
                  })}
                </span>
              </span>
              <span className={styles.chevron} aria-hidden="true">›</span>
            </button>
          ))}
        </nav>
      </div>
    );
  }

  const current = active ?? 'general';
  const pane = (
    <div className={styles.pane}>
      <div className={styles.paneHeader}>
        <h2 className={`modal__title ${styles.paneTitle}`}>{sectionTitle(current)}</h2>
        <SaveIndicator status={status} />
      </div>
      {renderSection(current)}
    </div>
  );

  if (phone) {
    return (
      <div className={`modal ${styles.modal}`} role="dialog" aria-modal="true" aria-label={t('settingsModal.title')}>
        <div className="modal__header">
          <button type="button" className={styles.back} onClick={() => onNavigate(null)}>
            <span aria-hidden="true">‹</span> {t('settingsModal.title')}
          </button>
          {closeButton}
        </div>
        <div className="modal__body">{pane}</div>
      </div>
    );
  }

  return (
    <div className={`modal ${styles.modal} ${styles.wide}`} role="dialog" aria-modal="true" aria-label={t('settingsModal.title')}>
      <div className="modal__header">
        <span className="modal__title">{t('settingsModal.title')}</span>
        {closeButton}
      </div>
      <div className={styles.split}>
        <nav className={styles.rail} aria-label={t('settingsModal.title')}>
          {sections.map(s => (
            <button
              key={s}
              type="button"
              className={`${styles.railItem} ${s === current ? styles.railItemActive : ''}`}
              aria-current={s === current ? 'page' : undefined}
              onClick={() => onNavigate(s)}
            >
              <SectionIcon section={s} className={styles.railIcon} />
              <span className={styles.railLabel}>{sectionTitle(s)}</span>
            </button>
          ))}
        </nav>
        <div className={styles.paneScroll}>{pane}</div>
      </div>
    </div>
  );
}

function SaveIndicator({ status }: { status: SaveStatus }) {
  const { t } = useTranslation();
  const text = status === 'saving' ? t('settingsModal.status.saving')
    : status === 'saved' ? t('settingsModal.status.saved')
    : status === 'error' ? t('settingsModal.status.error')
    : '';
  return (
    <span className={`${styles.saveStatus} ${status === 'error' ? styles.saveStatusError : ''}`} role="status" aria-live="polite">
      {text}
    </span>
  );
}
