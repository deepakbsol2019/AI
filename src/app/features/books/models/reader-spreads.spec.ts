import { spreadPages, stepReader, type ReaderPosition } from './reader-spreads';

describe('PDF facing-page boundaries and decorative covers', () => {
  it('visits every odd-length document page once per forward double spread between separate covers', () => {
    let position: ReaderPosition = { stage: 'front', page: 1 };
    const seen: number[][] = [];
    while (true) {
      const next = stepReader(position, 1, 5, 'double', true);
      if (!next) break;
      position = next;
      if (position.stage === 'pages') seen.push(spreadPages(position.page, 5, 'double'));
    }
    expect(seen).toEqual([[1, 2], [3, 4], [5]]);
    expect(position.stage).toBe('back');
    expect(stepReader(position, 1, 5, 'double', true)).toBeNull();
    const reverse: number[][] = [];
    while (true) {
      const next = stepReader(position, -1, 5, 'double', true);
      if (!next) break;
      position = next;
      if (position.stage === 'pages') reverse.push(spreadPages(position.page, 5, 'double'));
    }
    expect(reverse).toEqual([[5], [3, 4], [1, 2]]);
    expect(position.stage).toBe('front');
  });
  it('retains an even selected page inside its facing spread and never invents a final page', () => {
    expect(spreadPages(2, 4, 'double')).toEqual([1, 2]);
    expect(spreadPages(4, 4, 'double')).toEqual([3, 4]);
    expect(spreadPages(4, 4, 'single')).toEqual([4]);
    expect(stepReader({ stage: 'pages', page: 4 }, 1, 4, 'double', false)).toBeNull();
    expect(stepReader({ stage: 'pages', page: 1 }, -1, 4, 'single', false)).toBeNull();
    expect(stepReader({ stage: 'pages', page: 1 }, 1, 0, 'single', true)).toBeNull();
  });
});
