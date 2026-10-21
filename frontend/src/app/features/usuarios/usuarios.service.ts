import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { EdicionUsuarioRequest, Usuario, UsuarioRequest } from './usuario.models';

/** Los usuarios de la ferretería del Dueño (CU-02). Todo el recurso es solo del Dueño. */
@Injectable({ providedIn: 'root' })
export class UsuariosService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/usuarios`;

  /** Ordenados por nombre; con `incluirInactivos`, también los desactivados. */
  listar(incluirInactivos = false): Observable<Usuario[]> {
    const params = incluirInactivos ? new HttpParams().set('incluirInactivos', 'true') : undefined;
    return this.http.get<Usuario[]>(this.url, { params });
  }

  crear(request: UsuarioRequest): Observable<Usuario> {
    return this.http.post<Usuario>(this.url, request);
  }

  actualizar(id: number, request: EdicionUsuarioRequest): Observable<Usuario> {
    return this.http.put<Usuario>(`${this.url}/${id}`, request);
  }

  /** Desactivar corta el acceso del usuario en el acto (su token deja de valer); reactivar se lo devuelve. */
  cambiarActivo(id: number, activo: boolean): Observable<Usuario> {
    return this.http.patch<Usuario>(`${this.url}/${id}/activo`, { activo });
  }

  /** El Dueño le pone una contraseña nueva a un usuario de su ferretería, sin pedir la actual. */
  resetearContrasena(id: number, password: string): Observable<void> {
    return this.http.put<void>(`${this.url}/${id}/password`, { password });
  }
}
