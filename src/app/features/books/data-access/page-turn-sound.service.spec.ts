import { TestBed } from '@angular/core/testing';
import { PageTurnSoundService } from './page-turn-sound.service';

describe('Quiet page-turn audio resource', () => {
  it('never autoplays on enabling, uses low gain only for turn requests and releases sources/context', () => {
    const descriptor = Object.getOwnPropertyDescriptor(window, 'AudioContext');
    const source = {
      buffer: null,
      connect: vi.fn(),
      disconnect: vi.fn(),
      start: vi.fn(),
      stop: vi.fn(),
      onended: null,
    };
    const gain = {
      gain: {
        setValueAtTime: vi.fn(),
        linearRampToValueAtTime: vi.fn(),
        exponentialRampToValueAtTime: vi.fn(),
      },
      connect: vi.fn(),
      disconnect: vi.fn(),
    };
    const filter = {
      type: '',
      frequency: { value: 0 },
      Q: { value: 0 },
      connect: vi.fn(),
      disconnect: vi.fn(),
    };
    const audio = {
      state: 'running',
      sampleRate: 1000,
      currentTime: 12,
      destination: {},
      resume: vi.fn(async () => undefined),
      close: vi.fn(async () => undefined),
      createBuffer: vi.fn(() => ({ getChannelData: () => new Float32Array(160) })),
      createBufferSource: vi.fn(() => source),
      createBiquadFilter: () => filter,
      createGain: () => gain,
    };
    const construct = vi.fn(function () {
      return audio;
    });
    Object.defineProperty(window, 'AudioContext', { value: construct, configurable: true });
    try {
      TestBed.configureTestingModule({ providers: [PageTurnSoundService] });
      const sound = TestBed.inject(PageTurnSoundService);
      sound.turn();
      expect(construct).not.toHaveBeenCalled();
      sound.enable();
      expect(audio.resume).toHaveBeenCalledOnce();
      expect(source.start).not.toHaveBeenCalled();
      sound.turn();
      expect(source.start).toHaveBeenCalledOnce();
      expect(gain.gain.linearRampToValueAtTime).toHaveBeenCalledWith(0.035, 12.025);
      expect(source.stop).toHaveBeenCalledWith(12.16);
      sound.disable();
      expect(source.stop).toHaveBeenCalledTimes(2);
      sound.ngOnDestroy();
      expect(audio.close).toHaveBeenCalledOnce();
    } finally {
      if (descriptor) Object.defineProperty(window, 'AudioContext', descriptor);
      else Reflect.deleteProperty(window, 'AudioContext');
    }
  });
});
