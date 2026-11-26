import { DOCUMENT } from '@angular/common';
import { inject, Injectable, InjectionToken } from '@angular/core';
import type { PDFDocumentLoadingTask, PDFPageProxy } from 'pdfjs-dist';
import type { PdfLoadHandle, PdfReaderPage } from '../models/reader.models';

export type PdfEngine = Pick<typeof import('pdfjs-dist'), 'getDocument' | 'GlobalWorkerOptions'>;

/** The engine and worker are requested only after an actual reader is opened. */
export const PDF_ENGINE_LOADER = new InjectionToken<() => Promise<PdfEngine>>('PDF_ENGINE_LOADER', {
  providedIn: 'root',
  factory: () => () => import('pdfjs-dist'),
});

@Injectable({ providedIn: 'root' })
export class PdfReaderService {
  private readonly document = inject(DOCUMENT);
  private readonly loadEngine = inject(PDF_ENGINE_LOADER);

  load(blob: Blob): PdfLoadHandle {
    let destroyed = false;
    let task: PDFDocumentLoadingTask | null = null;
    const assetUrl = (path: string) => new URL(`assets/pdfjs/${path}`, this.document.baseURI).href;
    const promise = (async () => {
      const [engine, bytes] = await Promise.all([this.loadEngine(), blob.arrayBuffer()]);
      if (destroyed) throw new Error('PDF loading was cancelled.');
      engine.GlobalWorkerOptions.workerSrc = assetUrl('pdf.worker.min.mjs');
      task = engine.getDocument({
        data: new Uint8Array(bytes),
        cMapUrl: assetUrl('cmaps/'),
        cMapPacked: true,
        standardFontDataUrl: assetUrl('standard_fonts/'),
        wasmUrl: assetUrl('wasm/'),
        iccUrl: assetUrl('iccs/'),
        disableWorkerRendering: true,
        enableXfa: false,
      });
      const pdf = await task.promise;
      if (destroyed) throw new Error('PDF loading was cancelled.');
      return {
        pageCount: pdf.numPages,
        getPage: async (number: number) => this.page(await pdf.getPage(number)),
      };
    })();
    return {
      promise,
      destroy: async () => {
        if (destroyed) return;
        destroyed = true;
        await task?.destroy();
      },
    };
  }

  private page(page: PDFPageProxy): PdfReaderPage {
    const base = page.getViewport({ scale: 1 });
    return {
      width: base.width,
      height: base.height,
      render: (canvas, width, _theme) => {
        const viewport = page.getViewport({ scale: width / base.width });
        const ratio = Math.min(
          this.document.defaultView?.devicePixelRatio || 1,
          2,
          Math.sqrt(16_000_000 / (viewport.width * viewport.height)),
          16_384 / viewport.width,
          16_384 / viewport.height,
        );
        // Reassigning bitmap dimensions resets canvas graphics state between renders.
        canvas.width = Math.ceil(viewport.width * ratio);
        canvas.height = Math.ceil(viewport.height * ratio);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;
        const context = canvas.getContext('2d');
        if (!context) throw new Error('This browser could not create the PDF canvas.');
        const task = page.render({
          canvas,
          canvasContext: context,
          viewport,
          transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
          // Keep the original PDF pixels. The reader applies its optional dark appearance
          // only to the canvas element; PDF.js high-contrast/SVG recoloring is not used.
          background: '#ffffff',
        });
        return { promise: task.promise, cancel: () => task.cancel() };
      },
      cleanup: () => {
        page.cleanup();
      },
    };
  }
}
