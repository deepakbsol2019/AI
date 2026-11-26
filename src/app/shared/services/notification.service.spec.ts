import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  let notifications: NotificationService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    notifications = TestBed.inject(NotificationService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('keeps a notification for exactly fifteen seconds', () => {
    const id = notifications.show('Book ready', 'success');
    vi.advanceTimersByTime(14_999);
    expect(notifications.items()).toEqual([{ id, message: 'Book ready', severity: 'success' }]);
    vi.advanceTimersByTime(1);
    expect(notifications.items()).toEqual([]);
  });

  it('uses stable IDs and independent timers for consecutive identical messages', () => {
    const first = notifications.show('Download ready');
    vi.advanceTimersByTime(5_000);
    const second = notifications.show('Download ready');
    expect(first).not.toBe(second);
    expect(notifications.items().map((item) => item.id)).toEqual([first, second]);
    vi.advanceTimersByTime(10_000);
    expect(notifications.items()).toEqual([
      { id: second, message: 'Download ready', severity: 'info' },
    ]);
    vi.advanceTimersByTime(4_999);
    expect(notifications.items()[0].id).toBe(second);
    vi.advanceTimersByTime(1);
    expect(notifications.items()).toEqual([]);
  });

  it('manually dismisses only the selected notification without changing another expiry', () => {
    const first = notifications.show('First', 'error');
    const second = notifications.show('Second', 'warning');
    vi.advanceTimersByTime(2_000);
    notifications.dismiss(first);
    notifications.dismiss(99999);
    expect(notifications.items().map((item) => item.id)).toEqual([second]);
    vi.advanceTimersByTime(12_999);
    expect(notifications.items()[0].message).toBe('Second');
    vi.advanceTimersByTime(1);
    expect(notifications.items()).toEqual([]);
  });

  it('cancels pending expiry callbacks and clears items when its injector is destroyed', () => {
    notifications.show('Pending');
    notifications.show('Also pending');
    const dismiss = vi.spyOn(notifications, 'dismiss');
    TestBed.resetTestingModule();
    expect(notifications.items()).toEqual([]);
    notifications.show('Late response after teardown');
    expect(notifications.items()).toEqual([]);
    vi.advanceTimersByTime(30_000);
    expect(dismiss).not.toHaveBeenCalled();
  });
  it('clears prior-account messages and expiry work while allowing fresh notifications', () => {
    notifications.show('Private old-user filename.pdf', 'success');
    const dismiss = vi.spyOn(notifications, 'dismiss');
    notifications.clear();
    expect(notifications.items()).toEqual([]);
    const current = notifications.show('New account message');
    vi.advanceTimersByTime(14_999);
    expect(dismiss).not.toHaveBeenCalled();
    expect(notifications.items()).toEqual([
      { id: current, message: 'New account message', severity: 'info' },
    ]);
    vi.advanceTimersByTime(1);
    expect(notifications.items()).toEqual([]);
  });
});
