import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The root: the ambient gold/teal wash every screen sits on, and the router.
 * Signed-in screens render inside {@link ShellComponent}; the sign-in screens
 * render on their own.
 */
@Component({
  selector: 'mc-root',
  imports: [RouterOutlet],
  template: `
    <div class="atmosphere" aria-hidden="true">
      <div class="blob blob--gold"></div>
      <div class="blob blob--teal"></div>
      <div class="blob blob--rose"></div>
    </div>
    <router-outlet />
  `,
})
export class App {}
