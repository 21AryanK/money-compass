import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AiHealthResponse, AnswerResponse, AnswerValue, BackResponse, ChatIntro, ChatRequest, ChatResponse,
  LiteracyScoreResponse, NextQuestionResponse, PlanRequest, PlanResponse, RiskBand, RiskProfileResponse,
  SessionSummary, StartSessionResponse,
} from './api.types';

/**
 * One client for everything after sign-in: the questionnaire, results, plan,
 * assistant, history and model health. They are a single flow for the user,
 * so splitting them across services would only add imports.
 */
@Injectable({ providedIn: 'root' })
export class AssessmentService {

  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  // ---- questionnaire

  start(): Observable<StartSessionResponse> {
    return this.http.post<StartSessionResponse>(`${this.base}/questionnaire/start`, {});
  }

  next(sessionId: string): Observable<NextQuestionResponse> {
    return this.http.get<NextQuestionResponse>(`${this.base}/questionnaire/${sessionId}/next`);
  }

  answer(sessionId: string, questionCode: string, value: AnswerValue): Observable<AnswerResponse> {
    return this.http.post<AnswerResponse>(`${this.base}/questionnaire/${sessionId}/answer`, { questionCode, value });
  }

  back(sessionId: string): Observable<BackResponse> {
    return this.http.post<BackResponse>(`${this.base}/questionnaire/${sessionId}/back`, {});
  }

  complete(sessionId: string): Observable<{ sessionId: string; status: string }> {
    return this.http.post<{ sessionId: string; status: string }>(`${this.base}/questionnaire/${sessionId}/complete`, {});
  }

  // ---- results

  computeScore(sessionId: string): Observable<LiteracyScoreResponse> {
    return this.http.post<LiteracyScoreResponse>(`${this.base}/score/${sessionId}`, {});
  }

  regenerateScore(sessionId: string): Observable<LiteracyScoreResponse> {
    return this.http.post<LiteracyScoreResponse>(`${this.base}/score/${sessionId}/regenerate`, {});
  }

  computeRisk(sessionId: string): Observable<RiskProfileResponse> {
    return this.http.post<RiskProfileResponse>(`${this.base}/risk/${sessionId}`, {});
  }

  regenerateRisk(sessionId: string): Observable<RiskProfileResponse> {
    return this.http.post<RiskProfileResponse>(`${this.base}/risk/${sessionId}/regenerate`, {});
  }

  /** Plan around a different band; null goes back to the calculated one. */
  selectBand(sessionId: string, band: RiskBand | null): Observable<RiskProfileResponse> {
    return this.http.put<RiskProfileResponse>(`${this.base}/risk/${sessionId}/band`, { band });
  }

  plan(sessionId: string, request: PlanRequest): Observable<PlanResponse> {
    return this.http.post<PlanResponse>(`${this.base}/plan/${sessionId}`, request);
  }

  // ---- assistant, feedback, history, health

  chatIntro(sessionId: string, screen: string): Observable<ChatIntro> {
    return this.http.get<ChatIntro>(`${this.base}/chat/${sessionId}/intro`, { params: new HttpParams().set('screen', screen) });
  }

  chat(sessionId: string, request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.base}/chat/${sessionId}`, request);
  }

  /** rating null clears an earlier rating. */
  feedback(sessionId: string, target: 'SCORE' | 'RISK', band: RiskBand | null, draftIndex: number,
           rating: 'UP' | 'DOWN' | null): Observable<void> {
    return this.http.post<void>(`${this.base}/feedback`, { sessionId, target, band, draftIndex, rating });
  }

  sessions(): Observable<SessionSummary[]> {
    return this.http.get<SessionSummary[]>(`${this.base}/sessions`);
  }

  aiHealth(): Observable<AiHealthResponse> {
    return this.http.get<AiHealthResponse>(`${this.base}/health/ai`);
  }
}
