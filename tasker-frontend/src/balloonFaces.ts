import balloonUrl from './assets/balloon/balloon.webp';
import grinUrl from './assets/balloon/mouth-grin.webp';
import openSmileUrl from './assets/balloon/mouth-open-smile.webp';
import squiggleUrl from './assets/balloon/mouth-squiggle.webp';
import tongueOutUrl from './assets/balloon/mouth-tongue-out.webp';
import flatUrl from './assets/balloon/mouth-flat.webp';
import frownUrl from './assets/balloon/mouth-frown.webp';
import zigzagUrl from './assets/balloon/mouth-zigzag.webp';
import heartUrl from './assets/balloon/mouth-heart.webp';
import smirkUrl from './assets/balloon/mouth-smirk.webp';
import mustacheUrl from './assets/balloon/mouth-mustache.webp';
import stitchedUrl from './assets/balloon/mouth-stitched.webp';
import wobbleUrl from './assets/balloon/mouth-wobble.webp';

export const BALLOON_URL = balloonUrl;

/**
 * One art-directed balloon face: a mouth cut-out plus where it sits on the balloon.
 *
 * Every number is a percentage of the balloon's own box, so the face survives any
 * rendered size. They are **physical, not logical** coordinates and must not mirror
 * in RTL: the balloon is a photograph with asymmetric googly eyes, so a flipped
 * mouth would sit against the wrong eye. That is why the component applies them as
 * inline styles rather than CSS (see `logical-css.test.ts`, which guards stylesheets).
 */
export interface BalloonFace {
  id: string;
  mouthUrl: string;
  /** Centre of the mouth, as a percentage of balloon width / height. */
  x: number;
  y: number;
  /** Mouth width as a percentage of balloon width — its natural scale in the source photo. */
  width: number;
}

/**
 * The twelve mouths from `tools/Balloon.png`, each pinned to a fixed spot rather than
 * positioned at random: a mouth that lands a few percent off reads as a mistake, not
 * as variety. Randomness picks between whole curated faces, never coordinates.
 *
 * The shared anchor (50%, 65.5%) is where the source photo's own scale puts a mouth
 * under the eyes; only the two entries flagged below deviate from it.
 */
export const BALLOON_FACES: BalloonFace[] = [
  { id: 'grin',       mouthUrl: grinUrl,      x: 50, y: 65.5, width: 47.6 },
  { id: 'open-smile', mouthUrl: openSmileUrl, x: 50, y: 65.5, width: 46.5 },
  { id: 'squiggle',   mouthUrl: squiggleUrl,  x: 50, y: 65.5, width: 46.3 },
  { id: 'tongue-out', mouthUrl: tongueOutUrl, x: 50, y: 65.5, width: 46.1 },
  { id: 'flat',       mouthUrl: flatUrl,      x: 50, y: 65.5, width: 40.0 },
  { id: 'frown',      mouthUrl: frownUrl,     x: 50, y: 65.5, width: 43.0 },
  { id: 'zigzag',     mouthUrl: zigzagUrl,    x: 50, y: 65.5, width: 45.2 },
  // Widened from its natural 27.2%: the heart is a small closed shape and reads as a
  // smudge next to the wide pipe-cleaner mouths at the same scale.
  { id: 'heart',      mouthUrl: heartUrl,     x: 50, y: 65.5, width: 35.5 },
  { id: 'smirk',      mouthUrl: smirkUrl,     x: 50, y: 65.5, width: 37.8 },
  // A moustache belongs above the mouth line, not on it.
  { id: 'mustache',   mouthUrl: mustacheUrl,  x: 50, y: 62.0, width: 49.6 },
  { id: 'stitched',   mouthUrl: stitchedUrl,  x: 50, y: 65.5, width: 37.8 },
  { id: 'wobble',     mouthUrl: wobbleUrl,    x: 50, y: 65.5, width: 45.6 },
];

export function pickBalloonFace(random: number = Math.random()): BalloonFace {
  const index = Math.min(BALLOON_FACES.length - 1, Math.floor(random * BALLOON_FACES.length));
  return BALLOON_FACES[index];
}

/** The balloon never leans more than this far off vertical. */
export const MAX_TILT_DEG = 15;
/** Total vertical travel, as a multiple of viewport height (see `.rise` in the module CSS). */
export const TRAVEL_VH = 1.6;

export interface BalloonDrift {
  /** Horizontal displacement over the whole climb, in px. */
  driftPx: number;
  /** The lean actually used — below `MAX_TILT_DEG` whenever the clamp bites. */
  tiltDeg: number;
}

/**
 * Turns a random lean into a horizontal displacement, clamped so the balloon is still
 * on screen when it reaches the top. Unclamped, 15 degrees over 1.6 viewport heights is
 * ~43% of the height sideways — fine on a desktop, but on a phone it walks the balloon
 * off the edge halfway up, which reads as a glitch rather than as drift.
 */
export function balloonDrift(viewportWidth: number, viewportHeight: number, random: number = Math.random()): BalloonDrift {
  const tilt = (random * 2 - 1) * MAX_TILT_DEG;
  const ideal = Math.tan((tilt * Math.PI) / 180) * viewportHeight * TRAVEL_VH;
  const limit = viewportWidth * 0.3;
  const driftPx = Math.max(-limit, Math.min(limit, ideal));
  const effective = (Math.atan(driftPx / (viewportHeight * TRAVEL_VH)) * 180) / Math.PI;
  return { driftPx, tiltDeg: effective };
}
