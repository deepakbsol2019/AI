import { TestBed } from '@angular/core/testing';
import type { PDFPageProxy } from 'pdfjs-dist';
import { PDF_ENGINE_LOADER, PdfEngine, PdfReaderService } from './pdf-reader.service';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

function pdfBlob(): Blob {
  const blob = new Blob(['%PDF'], { type: 'application/pdf' });
  Object.defineProperty(blob, 'arrayBuffer', {
    value: async () => new Uint8Array([37, 80, 68, 70]).buffer,
  });
  return blob;
}

describe('PdfReaderService', () => {
  const render = vi.fn((_options: unknown) => ({ promise: Promise.resolve(), cancel: vi.fn() }));
  const cleanup = vi.fn();
  const getPage = vi.fn(
    async () =>
      ({
        getViewport: ({ scale }: { scale: number }) => ({
          width: 600 * scale,
          height: 800 * scale,
        }),
        render,
        cleanup,
      }) as unknown as PDFPageProxy,
  );
  const destroy = vi.fn(async () => undefined);
  const getDocument = vi.fn(() => ({
    promise: Promise.resolve({ numPages: 9, getPage }),
    destroy,
  }));
  const engine = { GlobalWorkerOptions: { workerSrc: '' }, getDocument } as unknown as PdfEngine;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [{ provide: PDF_ENGINE_LOADER, useValue: async () => engine }],
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      {} as CanvasRenderingContext2D,
    );
  });
  afterEach(() => vi.restoreAllMocks());

  it('uses local resources and preserves original PDF colors for every page theme without high-contrast filters', async () => {
    const handle = TestBed.inject(PdfReaderService).load(pdfBlob());
    const pdf = await handle.promise;
    expect(pdf.pageCount).toBe(9);
    expect(getPage).not.toHaveBeenCalled();
    expect(engine.GlobalWorkerOptions.workerSrc).toContain('/assets/pdfjs/pdf.worker.min.mjs');
    expect(getDocument).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.any(Uint8Array),
        cMapPacked: true,
        cMapUrl: expect.stringContaining('/assets/pdfjs/cmaps/'),
        standardFontDataUrl: expect.stringContaining('/assets/pdfjs/standard_fonts/'),
        wasmUrl: expect.stringContaining('/assets/pdfjs/wasm/'),
        iccUrl: expect.stringContaining('/assets/pdfjs/iccs/'),
      }),
    );
    const page = await pdf.getPage(4);
    expect(getPage).toHaveBeenCalledExactlyOnceWith(4);
    const canvas = document.createElement('canvas');
    const resetWidth = vi.spyOn(canvas, 'width', 'set');
    const resetHeight = vi.spyOn(canvas, 'height', 'set');
    const task = page.render(canvas, 800, 'dark');
    await task.promise;
    expect(render).toHaveBeenCalledWith(
      expect.objectContaining({
        canvas,
        background: '#ffffff',
      }),
    );
    expect(render.mock.calls[0][0]).not.toHaveProperty('pageColors');
    expect(canvas.style.width).toBe('800px');
    task.cancel();
    page.cleanup();
    expect(render.mock.results[0].value.cancel).toHaveBeenCalledTimes(1);
    expect(cleanup).toHaveBeenCalledTimes(1);
    await page.render(canvas, 800, 'original').promise;
    expect(render.mock.calls[1][0]).toEqual(expect.objectContaining({ background: '#ffffff' }));
    expect(render.mock.calls[1][0]).not.toHaveProperty('pageColors');
    await page.render(canvas, 800, 'inverted').promise;
    expect(render.mock.calls[2][0]).toEqual(expect.objectContaining({ background: '#ffffff' }));
    expect(render.mock.calls[2][0]).not.toHaveProperty('pageColors');
    // Even at unchanged size, each draw starts with a reset bitmap/context.
    expect(resetWidth).toHaveBeenCalledTimes(3);
    expect(resetHeight).toHaveBeenCalledTimes(3);
    expect(getDocument).toHaveBeenCalledTimes(1);
    expect(getPage).toHaveBeenCalledTimes(1);
    await handle.destroy();
    await handle.destroy();
    expect(destroy).toHaveBeenCalledTimes(1);
  });

  it('resizes the same canvas for another width while retaining a white, unmodified render', async () => {
    const handle = TestBed.inject(PdfReaderService).load(pdfBlob());
    const pdf = await handle.promise;
    const page = await pdf.getPage(2);
    const canvas = document.createElement('canvas');
    await page.render(canvas, 600, 'dark').promise;
    const firstBitmapWidth = canvas.width;
    await page.render(canvas, 900, 'original').promise;
    expect(canvas.width).toBeGreaterThan(firstBitmapWidth);
    expect(canvas.style.width).toBe('900px');
    expect(canvas.style.height).toBe('1200px');
    const options = render.mock.calls[1][0];
    expect(options).toMatchObject({ canvas, background: '#ffffff' });
    expect(options).not.toHaveProperty('pageColors');
    expect(getDocument).toHaveBeenCalledTimes(1);
    page.cleanup();
    await handle.destroy();
  });

  it('cancels before an asynchronous engine import can create a document or worker', async () => {
    const pending = deferred<PdfEngine>();
    TestBed.overrideProvider(PDF_ENGINE_LOADER, { useValue: () => pending.promise });
    const handle = TestBed.inject(PdfReaderService).load(pdfBlob());
    const rejected = expect(handle.promise).rejects.toThrow('cancelled');
    await handle.destroy();
    pending.resolve(engine);
    await rejected;
    expect(getDocument).not.toHaveBeenCalled();
  });
});
