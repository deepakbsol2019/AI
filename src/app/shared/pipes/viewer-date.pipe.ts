import { Pipe, PipeTransform } from '@angular/core';

const formatters = new Map<string, Intl.DateTimeFormat>();

/** Format only absolute instants. Naive API date-times must not be guessed or converted twice. */
export function formatViewerInstant(
  value: string | number | Date | null | undefined,
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
  locale = typeof navigator === 'undefined' ? 'en-GB' : navigator.language,
): string {
  if (value === null || value === undefined || value === '') return 'Not available';
  if (typeof value === 'string' && !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value.trim()))
    return 'Not available';
  const instant = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(instant.getTime())) return 'Not available';
  try {
    const key = `${locale}|${timeZone}`;
    let formatter = formatters.get(key);
    if (!formatter) {
      formatter = new Intl.DateTimeFormat(locale, {
        timeZone,
        year: 'numeric',
        month: 'short',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
        timeZoneName: 'short',
      });
      if (formatters.size >= 32) formatters.clear();
      formatters.set(key, formatter);
    }
    return `${formatter.format(instant)} (${timeZone})`;
  } catch {
    return 'Not available';
  }
}

@Pipe({ name: 'viewerDate', standalone: true, pure: false })
export class ViewerDatePipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined, timeZone?: string): string {
    // Resolve the current device's named zone on each display pass, including after travel/resume.
    return formatViewerInstant(value, timeZone);
  }
}
