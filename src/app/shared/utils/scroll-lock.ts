const OVERFLOW_PROPERTIES = new Set(['overflow', 'overflow-x', 'overflow-y']);

/** Lock the page and library viewport without losing their inline styles or scroll positions. */
export function lockLibraryScroll(document: Document): () => void {
  const elements = [
    ...new Set([
      document.documentElement,
      document.body,
      ...document.querySelectorAll<HTMLElement>('[data-library-scroll-region]'),
    ]),
  ];
  const snapshots = elements.map((element) => ({
    element,
    scrollTop: element.scrollTop,
    scrollLeft: element.scrollLeft,
    declarations: Array.from(element.style)
      .filter((property) => OVERFLOW_PROPERTIES.has(property))
      .map((property) => ({
        property,
        value: element.style.getPropertyValue(property),
        priority: element.style.getPropertyPriority(property),
      })),
  }));
  for (const { element } of snapshots) element.style.setProperty('overflow', 'hidden', 'important');

  let released = false;
  return () => {
    if (released) return;
    released = true;
    for (const { element, declarations, scrollTop, scrollLeft } of [...snapshots].reverse()) {
      for (const property of OVERFLOW_PROPERTIES) element.style.removeProperty(property);
      for (const { property, value, priority } of declarations) {
        element.style.setProperty(property, value, priority);
      }
      element.scrollTop = scrollTop;
      element.scrollLeft = scrollLeft;
    }
  };
}
