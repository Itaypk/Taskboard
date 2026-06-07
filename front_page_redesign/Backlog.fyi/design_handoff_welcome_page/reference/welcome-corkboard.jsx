// Welcome — Corkboard (standalone, refined)
// Fix: satellite notes live entirely in the side margins, never over the hero note.
// Login: two approaches wired to a tweak — single "Get started" → modal, or separate buttons.

const { useState, useEffect } = React;

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "loginStyle": "modal",
  "heroColor": "#f3d96b",
  "headline": "Tasks you keep",
  "headlineAccent": "actually doing.",
  "noteTilt": -1.2,
  "showSatellites": true,
  "channels": ["telegram", "google", "email"]
}/*EDITMODE-END*/;

const COLOR_EDGE = {
  "#f3d96b": "#d8bd55", // yellow
  "#f4c896": "#d6ab7c", // peach
  "#f3b5b5": "#d59999", // pink
  "#b8c8e0": "#9bacc7", // blue
  "#c6dcc2": "#a8bfa5", // mint
};

function WelcomeCorkboard() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [modalOpen, setModalOpen] = useState(false);
  const edge = COLOR_EDGE[t.heroColor] || "#d8bd55";

  // close modal on escape
  useEffect(() => {
    if (!modalOpen) return;
    const onKey = (e) => { if (e.key === 'Escape') setModalOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [modalOpen]);

  return (
    <div style={cb.viewport}>
      <div style={cb.stage} id="cork-stage">
        <div style={cb.grain} aria-hidden="true" />

        {/* Nav */}
        <header style={cb.nav}>
          <span className="logo-pill">Backlog.fyi</span>
          <nav style={cb.navRight}>
            <a style={cb.navLink} href="#">How it works</a>
            <a style={cb.navLink} href="#">Privacy</a>
            <a style={cb.navLink} href="#" onClick={(e) => { e.preventDefault(); setModalOpen(true); }}>Log in</a>
          </nav>
        </header>

        {/* Composition stage */}
        <div style={cb.board}>
          {/* ---------- Satellite notes — LEFT margin ---------- */}
          {t.showSatellites && (
            <React.Fragment>
              <div className="sticky pink" style={{ ...cb.sat, left: 8, top: 70, width: 250, transform: 'rotate(-5deg)' }}>
                <div className="tag">The problem</div>
                <h3 style={cb.satH}>Tasks get written down — then quietly buried.</h3>
              </div>

              <div className="sticky peach" style={{ ...cb.sat, left: 36, top: 332, width: 256, transform: 'rotate(3.5deg)' }}>
                <span className="tape" style={{ top: -9, left: 26, transform: 'rotate(-4deg)', width: 54 }} />
                <div className="tag">The payoff</div>
                <h3 style={cb.satH}>A week you can actually commit to.</h3>
                <div className="meta" style={{ fontSize: 13, marginTop: 6 }}>Not another list to ignore.</div>
              </div>

              {/* ---------- Satellite notes — RIGHT margin ---------- */}
              <div className="sticky blue" style={{ ...cb.sat, right: 8, top: 58, width: 262, transform: 'rotate(4.5deg)' }}>
                <div className="tag">The method</div>
                <h3 style={cb.satH}>Time-blocking — but you don't do the planning.</h3>
                <div className="meta" style={{ fontSize: 13, marginTop: 6 }}>The assistant proposes; you push back.</div>
              </div>

              <div className="sticky mint" style={{ ...cb.sat, right: 30, top: 338, width: 256, transform: 'rotate(-4deg)' }}>
                <span className="tape" style={{ top: -9, right: 30, transform: 'rotate(5deg)', width: 54 }} />
                <div className="tag">Yours alone</div>
                <h3 style={{ ...cb.satH, fontSize: 16 }}>No ads. No trackers. Encrypted at rest. ZDR&nbsp;AI.</h3>
              </div>
            </React.Fragment>
          )}

          {/* ---------- HERO note (center, never covered) ---------- */}
          <div style={cb.heroWrap}>
            <div style={{
              ...cb.heroSticky,
              background: t.heroColor,
              borderBottomRightRadius: 18,
              transform: `rotate(${t.noteTilt}deg)`,
            }}>
              <span className="tape" style={{ top: -11, left: 70, transform: 'rotate(-7deg)' }} />
              <span className="tape" style={{ top: -11, right: 90, transform: 'rotate(6deg)', width: 90 }} />
              {/* peeled corner */}
              <div style={{ ...cb.corner, background: `linear-gradient(135deg, transparent 0 49%, rgba(0,0,0,0.16) 50%, rgba(0,0,0,0.05) 60%, transparent 72%), linear-gradient(315deg, ${edge} 0 50%, transparent 50%)` }} aria-hidden="true" />

              <div className="tag" style={cb.heroTag}>This week · top of mind</div>
              <h1 style={cb.heroH1}>
                {t.headline}<br/>
                <em style={cb.heroEm}>{t.headlineAccent}</em>
              </h1>
              <p style={cb.heroSub}>
                Capture what's on your plate. Talk through a realistic week with the
                assistant. Agreed tasks land on your calendar as time blocks.
              </p>

              {/* CTA — depends on tweak */}
              {t.loginStyle === 'modal' ? (
                <div style={cb.ctaRow}>
                  <button className="btn btn-primary" style={cb.getStarted} onClick={() => setModalOpen(true)}>
                    Get started — it's free
                  </button>
                  <a href="#" style={cb.tryLink} onClick={(e) => e.preventDefault()}>or play in a sandbox →</a>
                </div>
              ) : (
                <div style={cb.btnStack}>
                  <div style={cb.btnRow}>
                    {t.channels.includes('telegram') && (
                      <button className="btn" style={cb.chTelegram}><TgIcon /> Continue with Telegram</button>
                    )}
                    {t.channels.includes('google') && (
                      <button className="btn" style={cb.chGoogle}><GIcon /> Continue with Google</button>
                    )}
                    {t.channels.includes('email') && (
                      <button className="btn" style={cb.chEmail}><MailIcon /> Continue with email</button>
                    )}
                  </div>
                  <a href="#" style={cb.tryLink} onClick={(e) => e.preventDefault()}>or play in a sandbox, no account needed →</a>
                </div>
              )}
            </div>

            {/* Pineapple — subtle nod, pinned bottom-right of hero */}
            <div style={cb.pineapple} className="photo-ph">🍍<br/>pineapple</div>
          </div>
        </div>

        {/* Footer */}
        <footer style={cb.footer}>
          <div className="mono" style={cb.fnote}>Status · work in progress · features may change</div>
          <div className="mono" style={cb.fnote}>Terms · Privacy · © 2026</div>
        </footer>

        {/* ---------- Login modal ---------- */}
        {modalOpen && (
          <LoginModal channels={t.channels} onClose={() => setModalOpen(false)} />
        )}
      </div>

      {/* Tweaks */}
      <TweaksPanel>
        <TweakSection label="Sign-in approach" />
        <TweakRadio
          label="Login style"
          value={t.loginStyle}
          options={[{ value: 'modal', label: 'One button + modal' }, { value: 'buttons', label: 'Separate buttons' }]}
          onChange={(v) => setTweak('loginStyle', v)}
        />
        <TweakSelect
          label="Channels offered"
          value={(t.channels || []).join(', ')}
          options={[
            { value: 'telegram', label: 'Telegram only' },
            { value: 'telegram, google', label: 'Telegram + Google' },
            { value: 'telegram, google, email', label: 'Telegram + Google + Email' },
            { value: 'telegram, email', label: 'Telegram + Email' },
          ]}
          onChange={(v) => setTweak('channels', v.split(',').map(s => s.trim()))}
        />

        <TweakSection label="Hero note" />
        <TweakColor
          label="Note color"
          value={t.heroColor}
          options={['#f3d96b', '#f4c896', '#f3b5b5', '#b8c8e0', '#c6dcc2']}
          onChange={(v) => setTweak('heroColor', v)}
        />
        <TweakSlider label="Tilt" value={t.noteTilt} min={-3} max={3} step={0.1} unit="°"
          onChange={(v) => setTweak('noteTilt', v)} />
        <TweakText label="Headline" value={t.headline} onChange={(v) => setTweak('headline', v)} />
        <TweakText label="Accent line" value={t.headlineAccent} onChange={(v) => setTweak('headlineAccent', v)} />

        <TweakSection label="Composition" />
        <TweakToggle label="Show side notes" value={t.showSatellites} onChange={(v) => setTweak('showSatellites', v)} />
      </TweaksPanel>
    </div>
  );
}

function LoginModal({ channels, onClose }) {
  return (
    <div style={cb.overlay} onClick={onClose}>
      <div style={cb.modal} onClick={(e) => e.stopPropagation()}>
        <button style={cb.modalClose} onClick={onClose} aria-label="Close">✕</button>

        <div className="tag" style={cb.modalTag}>Welcome in</div>
        <h2 style={cb.modalH}>Pick how you'd like to continue</h2>
        <p style={cb.modalSub}>One tap. We'll create your board if it's your first time.</p>

        <div style={cb.modalBtns}>
          {channels.includes('telegram') && (
            <button className="btn" style={{ ...cb.chTelegram, width: '100%' }}><TgIcon /> Continue with Telegram</button>
          )}
          {channels.includes('google') && (
            <button className="btn" style={{ ...cb.chGoogle, width: '100%' }}><GIcon /> Continue with Google</button>
          )}
          {channels.includes('email') && (
            <button className="btn" style={{ ...cb.chEmail, width: '100%' }}><MailIcon /> Continue with email</button>
          )}
        </div>

        <div style={cb.modalNote}>
          <span className="mono" style={{ fontSize: 10, letterSpacing: '0.12em', color: 'var(--ink-mute)' }}>
            More ways to sign in are on the way
          </span>
        </div>

        <div style={cb.modalDivider}><span style={cb.divLine} /><span className="mono" style={cb.divText}>just looking?</span><span style={cb.divLine} /></div>

        <a href="#" style={cb.sandboxBtn} onClick={(e) => e.preventDefault()}>
          Open a sandbox account <span style={{ opacity: 0.6 }}>→</span>
          <span style={cb.sandboxSub}>A fully-featured demo board. No sign-up.</span>
        </a>

        <p style={cb.modalFine}>
          By continuing you agree to our <u>Terms</u> and <u>Privacy Policy</u>. No ads, no trackers — ever.
        </p>
      </div>
    </div>
  );
}

/* ---------- Icons ---------- */
function TgIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 0a12 12 0 100 24 12 12 0 000-24zm5.6 8.2-1.9 8.9c-.1.6-.5.8-1.1.5l-3-2.2-1.4 1.4c-.2.2-.3.3-.6.3l.2-3 5.5-5c.2-.2 0-.3-.3-.1l-6.8 4.3-2.9-.9c-.6-.2-.6-.6.1-.9l11.5-4.4c.5-.2 1 .1.7 1.1z"/>
    </svg>
  );
}
function GIcon() {
  return (
    <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 01-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8z"/>
      <path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.1 0-5.8-2.1-6.7-5H1.3v3.1A12 12 0 0012 24z"/>
      <path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 010-4.6V6.6H1.3a12 12 0 000 10.8l4-3.1z"/>
      <path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8l3.4-3.4A12 12 0 0012 0 12 12 0 001.3 6.6l4 3.1c.9-2.9 3.6-5 6.7-5z"/>
    </svg>
  );
}
function MailIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="5" width="18" height="14" rx="2.5"/>
      <path d="M4 7l8 6 8-6"/>
    </svg>
  );
}

/* ---------- styles ---------- */
const STAGE_W = 1320, STAGE_H = 860;
const cb = {
  viewport: {
    position: 'fixed', inset: 0,
    display: 'grid', placeItems: 'center',
    background: 'radial-gradient(130% 100% at 50% 25%, #efe6cd 0%, #e6dcbd 55%, #ddcfa6 100%)',
    overflow: 'hidden',
  },
  stage: {
    position: 'relative',
    width: STAGE_W, height: STAGE_H,
    flex: '0 0 auto',
    transformOrigin: 'center center',
  },
  grain: {
    position: 'absolute', inset: 0, pointerEvents: 'none',
    backgroundImage:
      'radial-gradient(circle at 20% 30%, rgba(160,130,80,0.05) 0 1px, transparent 1px),' +
      'radial-gradient(circle at 70% 80%, rgba(160,130,80,0.04) 0 1px, transparent 1px)',
    backgroundSize: '6px 6px, 9px 9px',
  },

  nav: {
    position: 'absolute', top: 0, left: 0, right: 0, zIndex: 5,
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '26px 44px',
  },
  navRight: { display: 'flex', gap: 28 },
  navLink: {
    fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase',
    color: 'var(--ink-soft)', textDecoration: 'none',
  },

  board: {
    position: 'absolute', inset: 0,
    paddingTop: 96,
  },

  sat: {
    position: 'absolute',
    padding: '14px 16px 16px',
    borderRadius: '2px 2px 12px 2px',
    zIndex: 1,
  },
  satH: {
    fontFamily: 'var(--serif)', fontWeight: 700, fontSize: 17, lineHeight: 1.22,
    margin: 0, letterSpacing: '-0.01em',
  },

  heroWrap: {
    position: 'absolute',
    left: '50%', top: 130,
    transform: 'translateX(-50%)',
    width: 600,
    zIndex: 3,
  },
  heroSticky: {
    position: 'relative',
    padding: '38px 46px 38px',
    borderRadius: '2px 2px 18px 2px',
    boxShadow:
      '0 2px 2px rgba(70,50,20,0.08),' +
      '0 12px 26px rgba(70,50,20,0.14),' +
      '0 32px 64px -16px rgba(70,50,20,0.32)',
  },
  corner: {
    position: 'absolute', right: 0, bottom: 0,
    width: 24, height: 24, borderRadius: '0 0 18px 0',
  },
  heroTag: {
    fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase',
    color: 'var(--ink-soft)', marginBottom: 6,
  },
  heroH1: {
    fontFamily: 'var(--serif)', fontWeight: 700, fontSize: 62, lineHeight: 0.98,
    letterSpacing: '-0.025em', margin: '4px 0 18px', color: 'var(--ink)',
  },
  heroEm: { fontStyle: 'italic', fontWeight: 500, color: '#3b2a16' },
  heroSub: {
    fontFamily: 'var(--serif)', fontSize: 17, lineHeight: 1.45,
    margin: '0 0 26px', color: '#3b2e1c', maxWidth: 480,
  },

  ctaRow: { display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' },
  getStarted: { fontSize: 17, padding: '15px 26px' },
  tryLink: {
    fontFamily: 'var(--serif)', fontStyle: 'italic', fontSize: 15,
    color: 'var(--ink-soft)', textDecoration: 'underline',
    textUnderlineOffset: 4, textDecorationThickness: '1px',
  },

  btnStack: { display: 'flex', flexDirection: 'column', gap: 14 },
  btnRow: { display: 'flex', gap: 10, flexWrap: 'wrap' },
  chTelegram: { background: 'var(--telegram)', color: '#fff', boxShadow: '0 6px 16px -6px rgba(42,171,238,0.55)' },
  chGoogle: { background: '#fff', color: '#2a1f14', border: '1.5px solid rgba(60,40,15,0.2)' },
  chEmail: { background: 'transparent', color: 'var(--ink)', border: '1.5px solid var(--ink)' },

  pineapple: {
    position: 'absolute',
    right: -54, bottom: -40,
    width: 74, height: 92,
    fontSize: 20, lineHeight: 1.3,
    transform: 'rotate(5deg)',
    zIndex: 2,
  },

  footer: {
    position: 'absolute', bottom: 0, left: 0, right: 0, zIndex: 5,
    padding: '20px 44px',
    display: 'flex', justifyContent: 'space-between',
    borderTop: '1px solid rgba(120,90,40,0.12)',
  },
  fnote: { fontSize: 11, letterSpacing: '0.06em', color: 'var(--ink-mute)', textTransform: 'uppercase' },

  /* modal */
  overlay: {
    position: 'absolute', inset: 0, zIndex: 50,
    background: 'rgba(40,28,12,0.42)',
    backdropFilter: 'blur(3px)',
    display: 'grid', placeItems: 'center',
    animation: 'cbfade .18s ease',
  },
  modal: {
    position: 'relative',
    width: 420,
    background: 'var(--paper)',
    borderRadius: 16,
    padding: '34px 34px 26px',
    boxShadow: '0 30px 70px -20px rgba(30,20,8,0.55)',
    border: '1px solid rgba(120,90,40,0.16)',
    transform: 'rotate(-0.4deg)',
  },
  modalClose: {
    position: 'absolute', top: 16, right: 16,
    width: 30, height: 30, borderRadius: '50%',
    border: 'none', cursor: 'pointer',
    background: 'rgba(120,90,40,0.1)', color: 'var(--ink-soft)',
    fontSize: 13, fontFamily: 'var(--mono)',
  },
  modalTag: {
    fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.16em', textTransform: 'uppercase',
    color: 'var(--ink-mute)', marginBottom: 8,
  },
  modalH: {
    fontFamily: 'var(--serif)', fontWeight: 700, fontSize: 27, lineHeight: 1.1,
    letterSpacing: '-0.02em', margin: '0 0 6px', color: 'var(--ink)',
  },
  modalSub: { fontFamily: 'var(--serif)', fontSize: 15, color: 'var(--ink-soft)', margin: '0 0 22px' },
  modalBtns: { display: 'flex', flexDirection: 'column', gap: 10 },
  modalNote: { textAlign: 'center', marginTop: 14 },
  modalDivider: { display: 'flex', alignItems: 'center', gap: 12, margin: '18px 0 14px' },
  divLine: { flex: 1, height: 1, background: 'var(--rule)' },
  divText: { fontSize: 10, letterSpacing: '0.16em', textTransform: 'uppercase', color: 'var(--ink-mute)' },
  sandboxBtn: {
    display: 'flex', flexDirection: 'column', gap: 3,
    textAlign: 'center', textDecoration: 'none',
    padding: '12px 14px', borderRadius: 12,
    border: '1.5px dashed rgba(120,90,40,0.4)',
    fontFamily: 'var(--serif)', fontSize: 16, fontWeight: 600, color: 'var(--ink)',
  },
  sandboxSub: { fontFamily: 'var(--serif)', fontWeight: 400, fontStyle: 'italic', fontSize: 13, color: 'var(--ink-mute)' },
  modalFine: {
    fontFamily: 'var(--serif)', fontSize: 12, lineHeight: 1.5, color: 'var(--ink-mute)',
    textAlign: 'center', margin: '18px 0 0',
  },
};

window.WelcomeCorkboard = WelcomeCorkboard;
