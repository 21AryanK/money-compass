import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './api.types';

/**
 * Turns an error into one sentence a person can act on.
 *
 * Field-level validation messages win over the generic detail, because
 * "password must be 12 to 72 characters" tells someone what to change and
 * "One or more fields are invalid" does not.
 */
export function describeProblem(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong. Try again.';
  }

  if (error.status === 0) {
    return 'Cannot reach the server. Check that the backend is running.';
  }

  const problem = error.error as ProblemDetail | null;

  if (problem?.errors) {
    const first = Object.values(problem.errors)[0];
    if (first) {
      return first;
    }
  }

  return problem?.detail ?? `Request failed with status ${error.status}.`;
}
