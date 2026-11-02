import { inject } from '@angular/core';
import { CanActivateChildFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Con la contraseña elegida por otra persona (creada o reseteada por el super admin o el Dueño), todas las pantallas
 * del shell llevan a «Mi contraseña» hasta que el usuario la cambie. «Mi contraseña» se deja abrir y «Salir» sigue
 * andando (el login no está bajo el shell). El backend también lo exige: con la marca prendida, todo lo de /api salvo
 * /api/auth/me, el cambio de contraseña y el login responde 403 «Cambiá tu contraseña para seguir.» (hallazgo 5).
 */
export const contrasenaGuard: CanActivateChildFn = (ruta) => {
  const auth = inject(AuthService);
  if (!auth.debeCambiarContrasena() || ruta.routeConfig?.path === 'mi-contrasena') return true;
  return inject(Router).createUrlTree(['/mi-contrasena']);
};
