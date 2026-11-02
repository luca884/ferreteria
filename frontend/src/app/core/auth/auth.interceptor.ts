import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { esRutaPropia } from './ruta-propia';

/**
 * Agrega el Bearer a cada pedido y manda al login si el backend responde 401. Si responde 403 porque la contraseña está
 * por cambiar, relee la sesión y lleva a «Mi contraseña».
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();

  const conToken = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(conToken).pipe(
    catchError((err: unknown) => {
      // Hallazgo 5: le resetearon la contraseña con la sesión abierta y el servidor frena todo hasta que la cambie. La
      // sesión local todavía no tiene la marca: se relee y el guard lo deja solo en «Mi contraseña».
      if (err instanceof HttpErrorResponse && err.status === 403 && err.error?.motivo === 'CONTRASENA_POR_CAMBIAR') {
        auth.refrescarSesion().subscribe({
          next: () => void router.navigate(['/mi-contrasena']),
          error: () => undefined,
        });
      }
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
