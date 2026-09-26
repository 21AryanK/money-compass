import { Signal, WritableSignal, signal } from '@angular/core';

/**
 * Reveals text a word at a time at a slightly uneven pace, the way tokens
 * arrive from a model, into a signal a template can bind to. The model's
 * reply has already arrived in full; this only paces how it appears.
 * Falls back to showing everything at once under reduced motion.
 *
 * Returns a cancel function, so a newer reply can interrupt an older one.
 */
export function streamInto(target: WritableSignal<string>, text: string, done?: () => void): () => void {
  const reduced = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  if (reduced || !text) {
    target.set(text ?? '');
    done?.();
    return () => {};
  }
  const words = text.split(' ');
  let i = 0;
  let cancelled = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  target.set('');
  const step = () => {
    if (cancelled) return;
    i++;
    target.set(words.slice(0, i).join(' '));
    if (i < words.length) {
      timer = setTimeout(step, 18 + Math.random() * 34 + (Math.random() < 0.06 ? 130 : 0));
    } else {
      done?.();
    }
  };
  step();
  return () => {
    cancelled = true;
    if (timer) clearTimeout(timer);
  };
}

/** A signal plus a "still streaming" flag, for a blinking cursor. */
export class StreamedText {
  readonly text = signal('');
  readonly streaming = signal(false);
  private cancel: () => void = () => {};

  get value(): Signal<string> {
    return this.text;
  }

  play(text: string, done?: () => void): void {
    this.cancel();
    this.streaming.set(true);
    this.cancel = streamInto(this.text, text, () => {
      this.streaming.set(false);
      done?.();
    });
  }

  show(text: string): void {
    this.cancel();
    this.streaming.set(false);
    this.text.set(text);
  }

  /** Clears the text and shows the cursor while a request is in flight. */
  thinking(): void {
    this.cancel();
    this.text.set('');
    this.streaming.set(true);
  }

  stop(): void {
    this.cancel();
  }
}
