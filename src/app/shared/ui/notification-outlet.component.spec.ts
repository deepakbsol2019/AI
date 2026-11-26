import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { NotificationService } from '../services/notification.service';
import { NotificationOutletComponent } from './notification-outlet.component';

describe('NotificationOutletComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [NotificationOutletComponent] }));

  afterEach(() => {
    document.querySelectorAll('[data-notification-test]').forEach((element) => element.remove());
  });
  it('provides an accessible upload-status action linked to the accepted Upload ID', async () => {
    const fixture = TestBed.createComponent(NotificationOutletComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    TestBed.inject(NotificationService).show('Background work accepted. Upload ID: 41.', 'info', {
      label: 'View Upload Status',
      url: '/user/uploads?uploadId=41',
    });
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a.notification-action') as HTMLAnchorElement;
    expect(link.textContent).toBe('View Upload Status');
    expect(link.getAttribute('href')).toBe('/user/uploads?uploadId=41');
    (fixture.nativeElement.querySelector('.notification-close') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a.notification-action')).toBeNull();
  });

  it('shows accessible nonmodal messages without moving focus and offers manual dismissal', async () => {
    const fixture = TestBed.createComponent(NotificationOutletComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    const opener = document.createElement('button');
    opener.setAttribute('data-notification-test', 'opener');
    document.body.appendChild(opener);
    opener.focus();
    const notifications = TestBed.inject(NotificationService);
    notifications.show('Book ready', 'success');
    notifications.show('Download could not finish', 'error');
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(document.activeElement).toBe(opener);
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Book ready');
    expect(element.querySelector('[role="alert"]')?.getAttribute('aria-live')).toBe('assertive');
    expect(element.querySelector('[role="dialog"]')).toBeNull();
    const dismiss = element.querySelector('button') as HTMLButtonElement;
    expect(dismiss.getAttribute('aria-label')).toContain('Dismiss notification');
    dismiss.click();
    fixture.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(1);
    expect(notifications.items()[0].message).toBe('Download could not finish');
    (element.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(element.querySelector('.notification-stack')).toBeNull();
  });

  it('floats below the header and remeasures after resize without reserving empty layout', async () => {
    const headerHost = document.createElement('app-header');
    headerHost.setAttribute('data-notification-test', 'header');
    const header = document.createElement('header');
    headerHost.appendChild(header);
    document.body.appendChild(headerHost);
    const bounds = vi
      .spyOn(header, 'getBoundingClientRect')
      .mockReturnValue({ bottom: 82 } as DOMRect);
    const fixture = TestBed.createComponent(NotificationOutletComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('.notification-stack')).toBeNull();
    TestBed.inject(NotificationService).show('Ready below the controls', 'success');
    fixture.detectChanges();
    let stack = (fixture.nativeElement as HTMLElement).querySelector(
      '.notification-stack',
    ) as HTMLElement;
    expect(stack.style.top).toBe('90px');
    bounds.mockReturnValue({ bottom: 118 } as DOMRect);
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    stack = (fixture.nativeElement as HTMLElement).querySelector(
      '.notification-stack',
    ) as HTMLElement;
    expect(stack.style.top).toBe('126px');
  });

  it('uses only the active dialog outlet and restores the global outlet when the dialog closes', async () => {
    const global = TestBed.createComponent(NotificationOutletComponent);
    const modal = TestBed.createComponent(NotificationOutletComponent);
    const dialog = document.createElement('dialog');
    dialog.setAttribute('data-notification-test', 'dialog');
    const header = document.createElement('header');
    vi.spyOn(header, 'getBoundingClientRect').mockReturnValue({ bottom: 200 } as DOMRect);
    dialog.appendChild(header);
    document.body.appendChild(dialog);
    dialog.appendChild(modal.nativeElement);
    dialog.setAttribute('open', '');
    global.detectChanges();
    modal.detectChanges();
    await global.whenStable();
    await modal.whenStable();
    TestBed.inject(NotificationService).show('Ready in this dialog', 'success');
    global.detectChanges();
    modal.detectChanges();
    expect((global.nativeElement as HTMLElement).querySelectorAll('article')).toHaveLength(0);
    expect((modal.nativeElement as HTMLElement).querySelectorAll('article')).toHaveLength(1);
    expect(
      ((modal.nativeElement as HTMLElement).querySelector('.notification-stack') as HTMLElement)
        .style.top,
    ).toBe('208px');
    dialog.removeAttribute('open');
    await Promise.resolve();
    global.detectChanges();
    modal.detectChanges();
    expect((global.nativeElement as HTMLElement).querySelectorAll('article')).toHaveLength(1);
    expect((modal.nativeElement as HTMLElement).querySelectorAll('article')).toHaveLength(0);
  });
});
