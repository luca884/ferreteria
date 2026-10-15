import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Adónde cae cada rol al entrar a `/`: el Dueño y el Vendedor al mostrador (lo que más se usa), el
 * administrador (sin ferretería) a rubros. Sin sesión, directo al login.
 */
export const inicioPorRol: RedirectFunction = () => {
  const rol = inject(AuthService).sesion()?.rol;
  if (!rol) return '/login';
  return rol === 'SUPER_ADMIN' ? 'rubros' : 'mostrador';
};
