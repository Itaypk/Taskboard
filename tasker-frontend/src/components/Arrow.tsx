import styles from './Arrow.module.css';

interface ArrowProps {
  /**
   * "forward" points along the reading direction (onward, continue); "back" points against it
   * (return, cancel). Both render the same glyph, mirrored by CSS per document direction.
   */
  direction: 'forward' | 'back';
}

/**
 * A reading-direction-aware arrow for link and button affordances.
 *
 * Arrows used to be baked into the catalog strings themselves ("← Back to sign in"), which pins
 * them to LTR: a Hebrew or Arabic reader needs the mirror image, and a translator should not have
 * to remember to flip a glyph. The character is decorative next to its own label, so it is hidden
 * from assistive tech and carries no text of its own.
 */
export function Arrow({ direction }: ArrowProps) {
  return <span aria-hidden="true" className={`${styles.arrow} ${styles[direction]}`}>→</span>;
}
