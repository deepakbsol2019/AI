import { lockLibraryScroll } from './scroll-lock';

describe('lockLibraryScroll', () => {
  it('restores overflow values, priorities and scroll positions while preserving unrelated style changes', () => {
    const html = document.documentElement;
    const body = document.body;
    const htmlStyle = html.getAttribute('style');
    const bodyStyle = body.getAttribute('style');
    const region = document.createElement('div');
    region.dataset['libraryScrollRegion'] = '';
    region.style.setProperty('overflow-y', 'auto', 'important');
    region.style.setProperty('overflow-x', 'clip');
    region.scrollTop = 223;
    region.scrollLeft = 19;
    body.style.setProperty('overflow', 'scroll', 'important');
    html.style.setProperty('overflow-x', 'auto');
    document.body.appendChild(region);
    try {
      const release = lockLibraryScroll(document);
      for (const element of [html, body, region]) {
        expect(element.style.getPropertyValue('overflow')).toBe('hidden');
        expect(element.style.getPropertyPriority('overflow')).toBe('important');
      }
      region.style.color = 'blue';
      region.scrollTop = 0;
      region.scrollLeft = 0;
      release();
      expect(body.style.getPropertyValue('overflow')).toBe('scroll');
      expect(body.style.getPropertyPriority('overflow')).toBe('important');
      expect(html.style.getPropertyValue('overflow-x')).toBe('auto');
      expect(region.style.getPropertyValue('overflow-y')).toBe('auto');
      expect(region.style.getPropertyPriority('overflow-y')).toBe('important');
      expect(region.style.getPropertyValue('overflow-x')).toBe('clip');
      expect(region.style.color).toBe('blue');
      expect(region.scrollTop).toBe(223);
      expect(region.scrollLeft).toBe(19);
      region.scrollTop = 300;
      release();
      expect(region.scrollTop).toBe(300);
    } finally {
      region.remove();
      if (htmlStyle === null) html.removeAttribute('style');
      else html.setAttribute('style', htmlStyle);
      if (bodyStyle === null) body.removeAttribute('style');
      else body.setAttribute('style', bodyStyle);
    }
  });
});
