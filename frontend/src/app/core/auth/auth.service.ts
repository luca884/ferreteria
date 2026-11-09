import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AL_CERRAR_SESION } from './al-cerrar-sesion';
import { AltaTotp, Ingreso, LoginResponse, Sesion } from './auth.models';

const TOKEN_KEY = 'ferreteria.token';
const SESION_KEY = 'ferreteria.sesion';

/** Guarda el JWT y la sesion. El tenant lo decide el backend a partir del token. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly alCerrarSesion = inject(AL_CERRAR_SESION, { optional: true }) ?? [];

  private readonly _token = signal<string | null>(leer(TOKEN_KEY));
  private readonly _sesion = signal<Sesion | null>(leerJson<Sesion>(SESION_KEY));

  readonly token = this._token.asReadonly();
  readonly sesion = this._sesion.asReadonly();
  readonly autenticado = computed(() => this._token() !== null);
  readonly esDueno = computed(() => this._sesion()?.rol === 'DUENO');
  readonly esSuperAdmin = computed(() => this._sesion()?.rol === 'SUPER_ADMIN');
  /** La contraseña actual la eligió otra persona (alta o reseteo): hay que cambiarla antes de usar el resto. */
  readonly debeCambiarContrasena = computed(() => this._sesion()?.debeCambiarContrasena === true);

  /**
   * Con `segundoPaso` (el super admin) no guarda nada: la sesión recién se abre con {@link verificarTotp}. El
   * `tokenTotp` lo guarda en memoria quien llama, nunca acá ni en localStorage.
   */
  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.apiUrl}/auth/login`, { email, password })
      .pipe(
        tap((res) => {
          if (!res.segundoPaso) this.guardarIngreso(res);
        }),
      );
  }

  /** Alta del código de la app (totp-super-admin, S2): la clave y su QR. Mientras no se active, el QR no cambia. */
  altaTotp(tokenTotp: string): Observable<AltaTotp> {
    return this.http.post<AltaTotp>(`${environment.apiUrl}/auth/totp/alta`, { tokenTotp });
  }

  /** El código de la app del super admin: si está bien, guarda el token y la sesión igual que el login. */
  verificarTotp(tokenTotp: string, codigo: string): Observable<Ingreso> {
    return this.http
      .post<Ingreso>(`${environment.apiUrl}/auth/totp/verificar`, { tokenTotp, codigo })
      .pipe(tap((res) => this.guardarIngreso(res)));
  }

  private guardarIngreso({ token, sesion }: Ingreso): void {
    this._token.set(token);
    this._sesion.set(sesion);
    escribir(TOKEN_KEY, token);
    escribir(SESION_KEY, JSON.stringify(sesion));
  }

  /**
   * Cambia la contraseña propia. Una actual incorrecta vuelve como 400 con la clave `passwordActual`, nunca como 401:
   * el interceptor desloguea ante cualquier 401. Si sale bien, la marca de cambio obligatorio de la sesión se apaga.
   */
  cambiarContrasena(passwordActual: string, passwordNueva: string): Observable<void> {
    return this.http
      .put<void>(`${environment.apiUrl}/auth/password`, { passwordActual, passwordNueva })
      .pipe(tap(() => this.apagarMarcaDeContrasena()));
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

  private apagarMarcaDeContrasena(): void {
    const actual = this._sesion();
    if (!actual) return;
    const sesion = { ...actual, debeCambiarContrasena: false };
    this._sesion.set(sesion);
    escribir(SESION_KEY, JSON.stringify(sesion));
  }

  logout(): void {
    const sesion = this._sesion();
    if (sesion) {
      for (const paso of this.alCerrarSesion) {
        try {
          paso(sesion);
        } catch {
          /* el cierre sigue igual */
        }
      }
    }
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
