import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';
import { Rol } from './auth.models';
import { AuthService } from './auth.service';

/**
 * La pantalla de inicio de cada rol: el Dueño y el Vendedor al mostrador (lo que más se usa), el administrador (sin
 * ferretería) a la pantalla de ferreterías.
 */
export function inicioDe(rol: Rol): string {
  return rol === 'SUPER_ADMIN' ? 'admin/ferreterias' : 'mostrador';
}

/** Adónde cae cada rol al entrar a `/`. Sin sesión, directo al login. */
export const inicioPorRol: RedirectFunction = () => {
  const rol = inject(AuthService).sesion()?.rol;
  return rol ? inicioDe(rol) : '/login';
};
