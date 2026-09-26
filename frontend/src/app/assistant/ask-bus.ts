import { Injectable, signal } from '@angular/core';

/**
 * Lets any screen open Ask Compass, optionally with a question already asked
 * — the "Ask a follow-up →" buttons on the AI cards use it.
 */
@Injectable({ providedIn: 'root' })
export class AskBus {
  /** A question to ask as soon as the panel is open; a new object each time so repeats still fire. */
  readonly request = signal<{ question: string | null } | null>(null);

  open(question: string | null = null): void {
    this.request.set({ question });
  }
}
