import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Sin sesión manda al login recordando la URL pedida (returnUrl), para volver ahí después de ingresar. */
export const authGuard: CanActivateFn = (_ruta, estado) => {
  const auth = inject(AuthService);
  if (auth.autenticado()) return true;
  const router = inject(Router);
  return estado.url && estado.url !== '/'
    ? router.createUrlTree(['/login'], { queryParams: { returnUrl: estado.url } })
    : router.createUrlTree(['/login']);
};
