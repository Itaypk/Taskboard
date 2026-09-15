import { useState } from 'react';
import { BALLOON_URL, balloonDrift, pickBalloonFace } from '../balloonFaces';
import styles from './BalloonCelebration.module.css';

interface Props {
  /** Fired once the balloon has left the screen, so the caller can drop it from the DOM. */
  onDone: () => void;
}

const DURATION_MS = 4200;

/**
 * The "task done" reward: a goofy balloon drifting up and off the top of the screen.
 *
 * The face and the lean are drawn once, on mount, so a re-render never reshuffles a
 * balloon mid-flight. Mount one per completion (keyed) rather than reusing an instance.
 */
export default function BalloonCelebration({ onDone }: Props) {
  const [face] = useState(() => pickBalloonFace());
  const [drift] = useState(() => balloonDrift(window.innerWidth, window.innerHeight));

  return (
    <div className={styles.stage} aria-hidden="true">
      <div
        className={styles.rise}
        style={{
          '--balloon-drift': `${drift.driftPx.toFixed(1)}px`,
          '--balloon-tilt': `${drift.tiltDeg.toFixed(2)}deg`,
          '--balloon-duration': `${DURATION_MS}ms`,
        } as React.CSSProperties}
        // The sway loops forever and never fires `animationend`, but it does bubble
        // its iterations — only the climb finishing should retire the balloon.
        onAnimationEnd={e => { if (e.target === e.currentTarget) onDone(); }}
      >
        <div className={styles.sway}>
          <div className={styles.face}>
            <img className={styles.balloon} src={BALLOON_URL} alt="" draggable={false} />
            <img
              className={styles.mouth}
              src={face.mouthUrl}
              alt=""
              draggable={false}
              style={{ left: `${face.x}%`, top: `${face.y}%`, width: `${face.width}%` }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
