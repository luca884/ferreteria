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
      if (err instanceof HttpErrorResponse && err.status === 401 && !esDelIngreso(req.url)) {
        auth.logout();
        const queryParams: Record<string, string> = {};
        // Vuelve a donde estaba (solo rutas propias) una vez que se loguee de nuevo.
        const actual = router.url;
        if (esRutaPropia(actual)) queryParams['returnUrl'] = actual;
        // Con la sesión abierta, el 401 es que el servidor la cerró (ferretería suspendida, usuario dado de baja, rol
        // cambiado o vencida): el login lo avisa (bugs de la guía, S15). Sin token no había sesión que cerrar.
        if (token) queryParams['sesion'] = 'cerrada';
        if (Object.keys(queryParams).length > 0) {
          void router.navigate(['/login'], { queryParams });
        } else {
          void router.navigate(['/login']);
        }
      }
      return throwError(() => err);
    }),
  );
};

/**
 * El 401 del ingreso no cierra ninguna sesión: lo explica la pantalla del login. Eso vale para la contraseña y para el
 * segundo paso del super admin (`/auth/totp/alta` y `/auth/totp/verificar`: código mal o `tokenTotp` vencido).
 */
function esDelIngreso(url: string): boolean {
  return url.endsWith('/auth/login') || url.includes('/auth/totp/');
}
