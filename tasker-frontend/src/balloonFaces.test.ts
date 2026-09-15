import { describe, expect, it } from 'vitest';
import { BALLOON_FACES, MAX_TILT_DEG, TRAVEL_VH, balloonDrift, pickBalloonFace } from './balloonFaces';

describe('BALLOON_FACES', () => {
  it('has a distinct id and a mouth for every entry', () => {
    expect(BALLOON_FACES).toHaveLength(12);
    expect(new Set(BALLOON_FACES.map(f => f.id)).size).toBe(12);
    expect(BALLOON_FACES.every(f => f.mouthUrl.length > 0)).toBe(true);
  });

  it('keeps every mouth inside the balloon', () => {
    for (const face of BALLOON_FACES) {
      expect(face.x - face.width / 2).toBeGreaterThan(0);
      expect(face.x + face.width / 2).toBeLessThan(100);
      expect(face.y).toBeGreaterThan(50);
      expect(face.y).toBeLessThan(80);
    }
  });
});

describe('pickBalloonFace', () => {
  it('spans the whole table and never overflows on random() === 1', () => {
    expect(pickBalloonFace(0)).toBe(BALLOON_FACES[0]);
    expect(pickBalloonFace(0.999)).toBe(BALLOON_FACES[11]);
    expect(pickBalloonFace(1)).toBe(BALLOON_FACES[11]);
  });
});

describe('balloonDrift', () => {
  it('leans no further than the cap, in either direction', () => {
    for (const r of [0, 0.25, 0.5, 0.75, 1]) {
      const { tiltDeg } = balloonDrift(1440, 900, r);
      expect(Math.abs(tiltDeg)).toBeLessThanOrEqual(MAX_TILT_DEG + 1e-9);
    }
    expect(balloonDrift(1440, 900, 0).driftPx).toBeLessThan(0);
    expect(balloonDrift(1440, 900, 1).driftPx).toBeGreaterThan(0);
    expect(balloonDrift(1440, 900, 0.5).driftPx).toBeCloseTo(0, 6);
  });

  it('uses the full lean when there is room for it', () => {
    // A wide desktop: 15deg over 1.6 viewport heights is well inside the 30%-of-width clamp.
    const { driftPx, tiltDeg } = balloonDrift(2400, 900, 1);
    expect(tiltDeg).toBeCloseTo(MAX_TILT_DEG, 6);
    expect(driftPx).toBeCloseTo(Math.tan((MAX_TILT_DEG * Math.PI) / 180) * 900 * TRAVEL_VH, 6);
  });

  it('clamps on a phone so the balloon is still on screen at the top', () => {
    const { driftPx, tiltDeg } = balloonDrift(390, 844, 1);
    expect(Math.abs(driftPx)).toBeLessThanOrEqual(390 * 0.3 + 1e-9);
    expect(tiltDeg).toBeLessThan(MAX_TILT_DEG);
  });
});
