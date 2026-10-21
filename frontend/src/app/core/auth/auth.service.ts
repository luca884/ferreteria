import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginResponse, Sesion } from './auth.models';

const TOKEN_KEY = 'ferreteria.token';
const SESION_KEY = 'ferreteria.sesion';

/** Guarda el JWT y la sesion. El tenant lo decide el backend a partir del token. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _token = signal<string | null>(leer(TOKEN_KEY));
  private readonly _sesion = signal<Sesion | null>(leerJson<Sesion>(SESION_KEY));

  readonly token = this._token.asReadonly();
  readonly sesion = this._sesion.asReadonly();
  readonly autenticado = computed(() => this._token() !== null);
  readonly esDueno = computed(() => this._sesion()?.rol === 'DUENO');
  readonly esSuperAdmin = computed(() => this._sesion()?.rol === 'SUPER_ADMIN');

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.apiUrl}/auth/login`, { email, password })
      .pipe(
        tap((res) => {
          this._token.set(res.token);
          this._sesion.set(res.sesion);
          escribir(TOKEN_KEY, res.token);
          escribir(SESION_KEY, JSON.stringify(res.sesion));
        }),
      );
  }

  /**
   * Cambia la contraseña propia. Una actual incorrecta vuelve como 400 con la clave `passwordActual`, nunca como 401:
   * el interceptor desloguea ante cualquier 401.
   */
  cambiarContrasena(passwordActual: string, passwordNueva: string): Observable<void> {
    return this.http.put<void>(`${environment.apiUrl}/auth/password`, { passwordActual, passwordNueva });
  }

  /** Vuelve a pedir la sesión al servidor (por ejemplo después de editar el propio nombre). Si falla, la local queda como estaba. */
  refrescarSesion(): Observable<Sesion> {
    return this.http.get<Sesion>(`${environment.apiUrl}/auth/me`).pipe(
      tap((sesion) => {
        this._sesion.set(sesion);
        escribir(SESION_KEY, JSON.stringify(sesion));
      }),
    );
  }

  logout(): void {
    this._token.set(null);
    this._sesion.set(null);
    borrar(TOKEN_KEY);
    borrar(SESION_KEY);
  }
}

// localStorage puede fallar (modo privado, storage bloqueado): la app sigue andando sin persistir.
function leer(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function leerJson<T>(key: string): T | null {
  const raw = leer(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function escribir(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* sin persistencia */
  }
}

function borrar(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* sin persistencia */
  }
}
