import { DOCUMENT } from '@angular/common';
import { inject, Injectable, OnDestroy } from '@angular/core';

/** Short, quiet synthesized paper noise; no network asset or autoplay on opening/toggling. */
@Injectable()
export class PageTurnSoundService implements OnDestroy {
  private readonly document = inject(DOCUMENT);
  private context: AudioContext | null = null;
  private readonly sources = new Set<AudioBufferSourceNode>();
  enable(): void {
    try {
      const Audio = this.document.defaultView?.AudioContext;
      if (!Audio) return;
      this.context ??= new Audio();
      void this.context.resume().catch(() => undefined);
    } catch {
      /* Audio is optional when a device/browser does not expose it. */
    }
  }
  turn(): void {
    const context = this.context;
    if (!context || context.state !== 'running') return;
    try {
      const duration = 0.16,
        buffer = context.createBuffer(
          1,
          Math.ceil(context.sampleRate * duration),
          context.sampleRate,
        );
      const data = buffer.getChannelData(0);
      for (let i = 0; i < data.length; i++)
        data[i] = (Math.random() * 2 - 1) * Math.sin((Math.PI * i) / data.length);
      const source = context.createBufferSource(),
        filter = context.createBiquadFilter(),
        gain = context.createGain();
      source.buffer = buffer;
      filter.type = 'bandpass';
      filter.frequency.value = 1500;
      filter.Q.value = 0.6;
      gain.gain.setValueAtTime(0.0001, context.currentTime);
      gain.gain.linearRampToValueAtTime(0.035, context.currentTime + 0.025);
      gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + duration);
      source.connect(filter);
      filter.connect(gain);
      gain.connect(context.destination);
      this.sources.add(source);
      source.onended = () => {
        this.sources.delete(source);
        source.disconnect();
        filter.disconnect();
        gain.disconnect();
      };
      source.start();
      source.stop(context.currentTime + duration);
    } catch {
      /* A device change must not interrupt reading. */
    }
  }
  disable(): void {
    for (const source of this.sources) {
      try {
        source.stop();
      } catch {}
    }
    this.sources.clear();
  }
  ngOnDestroy(): void {
    this.disable();
    void this.context?.close().catch(() => undefined);
    this.context = null;
  }
}
