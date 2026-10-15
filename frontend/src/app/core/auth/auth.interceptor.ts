import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { esRutaPropia } from './ruta-propia';

/** Agrega el Bearer a cada pedido y manda al login si el backend responde 401. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();

  const conToken = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(conToken).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.endsWith('/auth/login')) {
        auth.logout();
        // Vuelve a donde estaba (solo rutas propias) una vez que se loguee de nuevo.
        const actual = router.url;
        if (esRutaPropia(actual)) {
          void router.navigate(['/login'], { queryParams: { returnUrl: actual } });
        } else {
          void router.navigate(['/login']);
        }
      }
      return throwError(() => err);
    }),
  );
};
