const OVERFLOW_PROPERTIES = new Set(['overflow', 'overflow-x', 'overflow-y']);

interface OverflowSnapshot {
  readonly scrollTop: number;
  readonly scrollLeft: number;
  readonly declarations: readonly { property: string; value: string; priority: string }[];
}

interface DocumentScrollLock {
  count: number;
  readonly snapshots: Map<HTMLElement, OverflowSnapshot>;
}

const documentLocks = new WeakMap<Document, DocumentScrollLock>();

/** Nested dialogs share a baseline, so any destruction order releases scrolling correctly. */
export function lockLibraryScroll(document: Document): () => void {
  const lock: DocumentScrollLock = documentLocks.get(document) ?? {
    count: 0,
    snapshots: new Map(),
  };
  documentLocks.set(document, lock);
  const elements = [
    ...new Set([
      document.documentElement,
      document.body,
      ...document.querySelectorAll<HTMLElement>('[data-library-scroll-region]'),
    ]),
  ];
  for (const element of elements) {
    if (!lock.snapshots.has(element)) {
      lock.snapshots.set(element, {
        scrollTop: element.scrollTop,
        scrollLeft: element.scrollLeft,
        declarations: Array.from(element.style)
          .filter((property) => OVERFLOW_PROPERTIES.has(property))
          .map((property) => ({
            property,
            value: element.style.getPropertyValue(property),
            priority: element.style.getPropertyPriority(property),
          })),
      });
    }
    element.style.setProperty('overflow', 'hidden', 'important');
  }
  ++lock.count;

  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (--lock.count > 0) return;
    documentLocks.delete(document);
    for (const [element, { declarations, scrollTop, scrollLeft }] of [
      ...lock.snapshots,
    ].reverse()) {
      for (const property of OVERFLOW_PROPERTIES) element.style.removeProperty(property);
      for (const { property, value, priority } of declarations) {
        element.style.setProperty(property, value, priority);
      }
      element.scrollTop = scrollTop;
      element.scrollLeft = scrollLeft;
    }
  };
}
