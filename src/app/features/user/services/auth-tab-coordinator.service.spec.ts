import { TestBed } from '@angular/core/testing';
import { AuthTabCoordinator } from './auth-tab-coordinator.service';

describe('Cross-tab refresh locking', () => {
  const previous = Object.getOwnPropertyDescriptor(navigator, 'locks');
  afterEach(() => {
    TestBed.resetTestingModule();
    if (previous) Object.defineProperty(navigator, 'locks', previous);
    else Reflect.deleteProperty(navigator, 'locks');
  });
  it('uses the same exclusive browser lock and does not start a second rotation until the first resolves', async () => {
    let queue = Promise.resolve<unknown>(undefined);
    const request = vi.fn((_name: string, _options: LockOptions, work: () => Promise<unknown>) => {
      const result = queue.then(work);
      queue = result.catch(() => undefined);
      return result;
    });
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } });
    TestBed.configureTestingModule({});
    const coordinator = TestBed.inject(AuthTabCoordinator);
    let complete: (() => void) | undefined;
    const calls: string[] = [];
    const first = coordinator.exclusive(
      () =>
        new Promise<void>((resolve) => {
          calls.push('first');
          complete = resolve;
        }),
    );
    const second = coordinator.exclusive(async () => {
      calls.push('second');
    });
    await Promise.resolve();
    expect(calls).toEqual(['first']);
    complete?.();
    await Promise.all([first, second]);
    expect(calls).toEqual(['first', 'second']);
    expect(request.mock.calls[0][0]).toBe(request.mock.calls[1][0]);
    expect(request.mock.calls[0][1].mode).toBe('exclusive');
  });
  it('fails closed when safe cross-tab locking is unavailable', async () => {
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
    const work = vi.fn();
    const coordinator = TestBed.inject(AuthTabCoordinator);
    await expect(coordinator.exclusive(work)).rejects.toThrow('Web Locks');
    expect(work).not.toHaveBeenCalled();
  });
});
