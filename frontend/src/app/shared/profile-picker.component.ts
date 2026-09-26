import { Component, input, model } from '@angular/core';
import { ProfileType } from '../core/api.types';

/** The three "which are you?" cards, used at registration and when changing profile. */
@Component({
  selector: 'mc-profile-picker',
  template: `
    <div class="profile-pick" role="radiogroup" aria-label="Which are you?">
      @for (p of profiles; track p.value) {
        <button type="button" class="profile-pick-card" role="radio" [class.selected]="value() === p.value"
                [attr.aria-checked]="value() === p.value" (click)="value.set(p.value)" [disabled]="disabled()">
          <span class="ppc-icon" aria-hidden="true">
            @switch (p.value) {
              @case ('STUDENT') {
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 5.2h7.2v13.6H5a1 1 0 01-1-1V5.2z"/><path d="M20 5.2h-7.2v13.6H19a1 1 0 001-1V5.2z"/></svg>
              }
              @case ('PROFESSIONAL') {
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3.5" y="8" width="17" height="11" rx="1.8"/><path d="M8.5 8V6.3a2 2 0 012-2h3a2 2 0 012 2V8"/></svg>
              }
              @case ('RETIREE') {
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="14" r="4"/><path d="M4 19.5h16M12 4.5v3M5.3 8.3l2 2M18.7 8.3l-2 2"/></svg>
              }
            }
          </span>
          <span class="ppc-text"><span class="ppc-title">{{ p.title }}</span><span class="ppc-desc">{{ p.desc }}</span></span>
        </button>
      }
    </div>
  `,
})
export class ProfilePickerComponent {
  readonly value = model<ProfileType | null>(null);
  readonly disabled = input(false);

  protected readonly profiles: { value: ProfileType; title: string; desc: string }[] = [
    { value: 'STUDENT', title: 'Student',
      desc: "Just starting out. We'll focus on budgeting basics, an emergency fund, and starting small with SIPs." },
    { value: 'PROFESSIONAL', title: 'Working professional',
      desc: "Steady income. We'll cover 80C, NPS, and building a long-term equity-and-debt mix." },
    { value: 'RETIREE', title: 'Retired',
      desc: "Preserving what you've built. We'll weight your plan toward capital protection over growth." },
  ];
}
