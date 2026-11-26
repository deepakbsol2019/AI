export type ReaderPageMode = 'single' | 'double';
export type ReaderStage = 'front' | 'pages' | 'back';
export interface ReaderPosition {
  stage: ReaderStage;
  page: number;
}

/** Page numbers always refer to actual PDF pages; covers are separate presentation states. */
export function spreadPages(page: number, count: number, mode: ReaderPageMode): number[] {
  if (count < 1) return [];
  const current = Math.min(count, Math.max(1, page));
  const start = mode === 'double' ? current - ((current - 1) % 2) : current;
  return mode === 'double' && start < count ? [start, start + 1] : [start];
}

export function stepReader(
  position: ReaderPosition,
  direction: -1 | 1,
  count: number,
  mode: ReaderPageMode,
  covers: boolean,
): ReaderPosition | null {
  if (count < 1) return null;
  if (position.stage === 'front') return direction === 1 ? { stage: 'pages', page: 1 } : null;
  if (position.stage === 'back') return direction === -1 ? { stage: 'pages', page: count } : null;
  const pages = spreadPages(position.page, count, mode);
  const target = direction === 1 ? pages[pages.length - 1] + 1 : pages[0] - 1;
  if (target < 1) return covers ? { stage: 'front', page: 1 } : null;
  if (target > count) return covers ? { stage: 'back', page: count } : null;
  return { stage: 'pages', page: target };
}
