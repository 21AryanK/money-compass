import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { MeResponse, ProfileType, TokenResponse } from './api.types';

const TOKEN_KEY = 'mc.token';
const EXPIRY_KEY = 'mc.expiresAt';

@Injectable({ providedIn: 'root' })
export class AuthService {

  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  private readonly tokenSignal = signal<string | null>(this.readStoredToken());
  private readonly userSignal = signal<MeResponse | null>(null);

  readonly token = this.tokenSignal.asReadonly();
  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  register(email: string, password: string, profileType: ProfileType): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/auth/register`, { email, password, profileType })
      .pipe(tap((res) => this.store(res)));
  }

  login(email: string, password: string): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/auth/login`, { email, password })
      .pipe(tap((res) => this.store(res)));
  }

  /** Restores the signed-in user after a page reload. */
  loadMe(): Observable<MeResponse> {
    return this.http
      .get<MeResponse>(`${this.base}/me`)
      .pipe(tap((user) => this.userSignal.set(user)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(EXPIRY_KEY);
    this.tokenSignal.set(null);
    this.userSignal.set(null);
  }

  private store(res: TokenResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(EXPIRY_KEY, res.expiresAt);
    this.tokenSignal.set(res.token);
  }

  /**
   * Drops an expired token at startup rather than letting the first request
   * fail with a 401 the user has to interpret. There is no refresh token, so an
   * expired token means signing in again.
   */
  private readStoredToken(): string | null {
    const token = localStorage.getItem(TOKEN_KEY);
    const expiresAt = localStorage.getItem(EXPIRY_KEY);
    if (!token || !expiresAt) {
      return null;
    }
    if (new Date(expiresAt).getTime() <= Date.now()) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(EXPIRY_KEY);
      return null;
    }
    return token;
  }
}
