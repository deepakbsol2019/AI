import { formatViewerInstant, ViewerDatePipe } from './viewer-date.pipe';

describe('Current-viewer UTC instant display', () => {
  it('shows the same stored instant in India and London without using the uploader zone', () => {
    const instant = '2026-01-15T12:00:00Z';
    expect(formatViewerInstant(instant, 'Asia/Kolkata', 'en-GB')).toContain('17:30:00');
    expect(formatViewerInstant(instant, 'Europe/London', 'en-GB')).toContain('12:00:00');
    expect(formatViewerInstant(instant, 'Asia/Kolkata', 'en-GB')).toContain('(Asia/Kolkata)');
  });
  it('applies named-zone daylight saving rules without a hardcoded offset', () => {
    expect(formatViewerInstant('2026-07-15T12:00:00Z', 'Europe/London', 'en-GB')).toContain(
      '13:00:00',
    );
    expect(formatViewerInstant('2026-01-15T12:00:00Z', 'Europe/London', 'en-GB')).toContain(
      '12:00:00',
    );
  });
  it('respects explicit API offsets and never double-converts an instant', () => {
    expect(formatViewerInstant('2026-07-15T17:30:00+05:30', 'Europe/London', 'en-GB')).toBe(
      formatViewerInstant('2026-07-15T12:00:00Z', 'Europe/London', 'en-GB'),
    );
  });
  it('labels missing, malformed, offset-free or invalid-zone inputs instead of guessing', () => {
    for (const value of [null, undefined, '', 'not-a-date', '2026-01-15T12:00:00'])
      expect(formatViewerInstant(value, 'Europe/London')).toBe('Not available');
    expect(formatViewerInstant('2026-01-15T12:00:00Z', 'bad-zone')).toBe('Not available');
  });
  it('defaults to the current browser named timezone and supports Date-valued instants', () => {
    const value = new Date('2026-07-15T12:00:00Z');
    expect(new ViewerDatePipe().transform(value)).toContain(
      `(${Intl.DateTimeFormat().resolvedOptions().timeZone})`,
    );
  });
});
