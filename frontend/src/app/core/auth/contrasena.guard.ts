import { inject } from '@angular/core';
import { CanActivateChildFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Con la contraseña elegida por otra persona (creada o reseteada por el super admin o el Dueño), todas las pantallas
 * del shell llevan a «Mi contraseña» hasta que el usuario la cambie. «Mi contraseña» se deja abrir y «Salir» sigue
 * andando (el login no está bajo el shell). Es del frontend nomás: el backend no frena los demás endpoints.
 */
export const contrasenaGuard: CanActivateChildFn = (ruta) => {
  const auth = inject(AuthService);
  if (!auth.debeCambiarContrasena() || ruta.routeConfig?.path === 'mi-contrasena') return true;
  return inject(Router).createUrlTree(['/mi-contrasena']);
};
