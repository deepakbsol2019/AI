import { lockLibraryScroll } from './scroll-lock';

import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, input, OnDestroy } from '@angular/core';
import { TestBed } from '@angular/core/testing';

const destructionOrder: string[] = [];

@Component({ selector: 'test-locking-dialog', template: '' })
class LockingDialog implements OnDestroy {
  readonly name = input.required<string>();
  private readonly release = lockLibraryScroll(inject(DOCUMENT));

  ngOnDestroy(): void {
    destructionOrder.push(this.name());
    this.release();
  }
}

@Component({
  imports: [LockingDialog],
  template: `<div data-library-scroll-region></div>
    <test-locking-dialog name="popup" />
    <test-locking-dialog name="reader" />`,
})
class DialogHost {
  constructor() {
    inject(DestroyRef).onDestroy(() => destructionOrder.push('parent'));
  }
}

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
  it('retains the first baseline until both nested locks release, including out-of-order destruction', () => {
    const html = document.documentElement;
    const body = document.body;
    const htmlStyle = html.getAttribute('style');
    const bodyStyle = body.getAttribute('style');
    const region = document.createElement('div');
    region.dataset['libraryScrollRegion'] = '';
    region.style.setProperty('overflow-y', 'auto', 'important');
    region.style.setProperty('overflow-x', 'clip');
    region.scrollTop = 187;
    region.scrollLeft = 11;
    html.style.setProperty('overflow-y', 'auto');
    body.style.setProperty('overflow', 'scroll', 'important');
    body.appendChild(region);
    const releasePopup = lockLibraryScroll(document);
    const releaseReader = lockLibraryScroll(document);
    try {
      region.style.color = 'red';
      region.scrollTop = 42;
      releasePopup();
      releasePopup();
      for (const element of [html, body, region]) {
        expect(element.style.getPropertyValue('overflow')).toBe('hidden');
        expect(element.style.getPropertyPriority('overflow')).toBe('important');
      }
      expect(region.scrollTop).toBe(42);
      releaseReader();
      expect(html.style.getPropertyValue('overflow-y')).toBe('auto');
      expect(body.style.getPropertyValue('overflow')).toBe('scroll');
      expect(body.style.getPropertyPriority('overflow')).toBe('important');
      expect(region.style.getPropertyValue('overflow-y')).toBe('auto');
      expect(region.style.getPropertyPriority('overflow-y')).toBe('important');
      expect(region.style.getPropertyValue('overflow-x')).toBe('clip');
      expect(region.style.color).toBe('red');
      expect(region.scrollTop).toBe(187);
      expect(region.scrollLeft).toBe(11);
      region.scrollTop = 250;
      releaseReader();
      expect(region.scrollTop).toBe(250);
    } finally {
      releasePopup();
      releaseReader();
      region.remove();
      if (htmlStyle === null) html.removeAttribute('style');
      else html.setAttribute('style', htmlStyle);
      if (bodyStyle === null) body.removeAttribute('style');
      else body.setAttribute('style', bodyStyle);
    }
  });

  it('also captures new library regions acquired while another dialog remains open', () => {
    const region = document.createElement('div');
    region.dataset['libraryScrollRegion'] = '';
    region.style.setProperty('overflow-y', 'auto', 'important');
    region.scrollTop = 66;
    const releaseFirst = lockLibraryScroll(document);
    document.body.appendChild(region);
    const releaseSecond = lockLibraryScroll(document);
    try {
      expect(region.style.getPropertyValue('overflow')).toBe('hidden');
      releaseFirst();
      expect(region.style.getPropertyValue('overflow')).toBe('hidden');
      releaseSecond();
      expect(region.style.getPropertyValue('overflow-y')).toBe('auto');
      expect(region.style.getPropertyPriority('overflow-y')).toBe('important');
      expect(region.scrollTop).toBe(66);
    } finally {
      releaseFirst();
      releaseSecond();
      region.remove();
    }
  });

  it('restores scrolling when Angular destroys popup and reader children before their parent', () => {
    TestBed.configureTestingModule({ imports: [DialogHost] });
    const html = document.documentElement;
    const body = document.body;
    const htmlOriginal = html.getAttribute('style');
    const bodyOriginal = body.getAttribute('style');
    html.style.setProperty('overflow-y', 'auto', 'important');
    body.style.setProperty('overflow-x', 'clip');
    const htmlBaseline = html.style.cssText;
    const bodyBaseline = body.style.cssText;
    destructionOrder.length = 0;
    const fixture = TestBed.createComponent(DialogHost);
    try {
      fixture.detectChanges();
      const region = fixture.nativeElement.querySelector(
        '[data-library-scroll-region]',
      ) as HTMLElement;
      expect(body.style.getPropertyValue('overflow')).toBe('hidden');
      fixture.destroy();
      expect(destructionOrder).toEqual(['popup', 'reader', 'parent']);
      expect(html.style.cssText).toBe(htmlBaseline);
      expect(body.style.cssText).toBe(bodyBaseline);
      expect(region.style.getPropertyValue('overflow')).toBe('');
    } finally {
      fixture.destroy();
      if (htmlOriginal === null) html.removeAttribute('style');
      else html.setAttribute('style', htmlOriginal);
      if (bodyOriginal === null) body.removeAttribute('style');
      else body.setAttribute('style', bodyOriginal);
    }
  });
});
