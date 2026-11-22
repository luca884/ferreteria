import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DISPOSICION_ORIGINAL, Disposicion } from '../../layout/menu';
import { MenuResuelto } from './menu.models';

@Injectable({ providedIn: 'root' })
export class MenuService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/menu`;

  /** La disposición del usuario del token. La de fábrica vive en el frontend. */
  obtener(): Observable<Disposicion> {
    return this.http.get<MenuResuelto>(this.url).pipe(map(({ disposicion }) => disposicion ?? DISPOSICION_ORIGINAL));
  }
}
