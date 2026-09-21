/**
 * Route shell. Deliberately light: only what an anonymous first paint needs is imported eagerly
 * (auth state, the login page, the footer). Everything else — the signed-in board, the markdown
 * policy pages, the one-off confirmation pages — is a separate chunk, so the landing page a new
 * visitor (or a PageSpeed run) downloads no longer carries the whole application. The board is
 * split by hand rather than with `lazy` (see `lazyComponent`); everything else uses `lazy`.
 */
import { lazy, useEffect, Suspense, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Routes, Route } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import AppFooter from './components/AppFooter';
import { lazyComponent, useLazyComponent } from './lazyComponent';
import { notifyToast } from './toast';
import './App.css';

// Not `lazy`: the board is the one chunk that races the signed-in first paint, and a Suspense
// fallback commit there costs a flat 300 ms of React's anti-flicker throttle. See `lazyComponent`.
const boardChunk = lazyComponent(() => import('./Board'));
const NotFoundPage = lazy(() => import('./NotFoundPage').then(m => ({ default: m.NotFoundPage })));
const EmailLoginConfirmPage = lazy(() => import('./auth/EmailLoginConfirmPage').then(m => ({ default: m.EmailLoginConfirmPage })));
const EmailVerifyConfirmPage = lazy(() => import('./auth/EmailVerifyConfirmPage').then(m => ({ default: m.EmailVerifyConfirmPage })));
const InvitePage = lazy(() => import('./auth/InvitePage').then(m => ({ default: m.InvitePage })));
const TermsPage = lazy(() => import('./auth/PolicyPage').then(m => ({ default: m.TermsPage })));
const PrivacyPage = lazy(() => import('./auth/PolicyPage').then(m => ({ default: m.PrivacyPage })));
const AboutPage = lazy(() => import('./auth/PolicyPage').then(m => ({ default: m.AboutPage })));
const FaqPage = lazy(() => import('./auth/PolicyPage').then(m => ({ default: m.FaqPage })));

/**
 * Shared fallback while a route chunk arrives. Same markup as the auth-bootstrap placeholder, so a
 * cold load that is both fetching `/me` and fetching the board chunk doesn't flicker between two
 * different "loading" states.
 */
function RouteFallback() {
  const { t } = useTranslation();
  return <div className="board-wrap"><div className="board board--empty">{t('app.loading')}</div></div>;
}

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<RouteFallback />}>{children}</Suspense>;
}

export default function App() {
  // Only anonymous visitors get the footer language switcher — see AppFooter.
  const { state } = useAuth();

  return (
    <>
      <Routes>
        <Route path="/terms" element={<Lazy><TermsPage /></Lazy>} />
        <Route path="/privacy" element={<Lazy><PrivacyPage /></Lazy>} />
        <Route path="/about" element={<Lazy><AboutPage /></Lazy>} />
        <Route path="/faq" element={<Lazy><FaqPage /></Lazy>} />
        <Route path="/email-login" element={<Lazy><EmailLoginConfirmPage /></Lazy>} />
        <Route path="/email-verify" element={<Lazy><EmailVerifyConfirmPage /></Lazy>} />
        <Route path="/invite" element={<Lazy><InvitePage /></Lazy>} />
        <Route path="/" element={<AuthShell />} />
        <Route path="/settings" element={<AuthShell />} />
        <Route path="/settings/:tab" element={<AuthShell />} />
        <Route path="*" element={<Lazy><NotFoundPage /></Lazy>} />
      </Routes>
      <AppFooter showLanguage={state.status !== 'authenticated'} />
    </>
  );
}

function AuthShell() {
  const { state, signOut } = useAuth();

  if (state.status === 'loading') {
    return <RouteFallback />;
  }

  if (state.status === 'unauthenticated') {
    return <LoginPage />;
  }

  return <BoardRoute onSignOut={signOut} />;
}

/**
 * Split out of `AuthShell` so the chunk request starts only once the visitor is known to be signed
 * in — `useLazyComponent` preloads on render, and a hook above `AuthShell`'s early returns would
 * put the whole signed-in surface on the anonymous entry path.
 */
function BoardRoute({ onSignOut }: { onSignOut: () => Promise<void> }) {
  const { t } = useTranslation();
  const { component: Board, failed } = useLazyComponent(boardChunk);

  // A chunk that won't load is nearly always a tab holding an `index.html` from before a redeploy,
  // pointing at asset hashes that no longer exist. Offer the same refresh the version nudge does,
  // rather than leaving the placeholder up forever.
  useEffect(() => {
    if (!failed) return;
    notifyToast({
      key: 'app-update',
      message: t('updateBanner.message'),
      durationMs: 0,
      action: { label: t('updateBanner.refresh'), onClick: () => window.location.reload() },
    });
  }, [failed, t]);

  if (!Board) {
    return <RouteFallback />;
  }

  return <Board onSignOut={onSignOut} />;
}
