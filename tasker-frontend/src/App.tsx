/**
 * Route shell. Deliberately light: only what an anonymous first paint needs is imported eagerly
 * (auth state, the login page, the footer). Everything else — the signed-in board, the markdown
 * policy pages, the one-off confirmation pages — is a `lazy` chunk, so the landing page a new
 * visitor (or a PageSpeed run) downloads no longer carries the whole application.
 */
import { lazy, Suspense, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Routes, Route } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import AppFooter from './components/AppFooter';
import './App.css';

const Board = lazy(() => import('./Board'));
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

  return <Lazy><Board onSignOut={signOut} /></Lazy>;
}
