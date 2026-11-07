import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateChildFn, Data, Router } from '@angular/router';
import { Avisos } from '../avisos/avisos';
import { Rol } from './auth.models';
import { AuthService } from './auth.service';
import { inicioDe } from './inicio-por-rol';

/** Las pantallas de la ferretería que usan los dos: el mostrador, la caja, los clientes… */
export const DUENO_Y_VENDEDOR: readonly Rol[] = ['DUENO', 'VENDEDOR'];
export const SOLO_DUENO: readonly Rol[] = ['DUENO'];
/** Las pantallas de la plataforma (ferreterías): ni el Dueño ni el Vendedor. */
export const SOLO_SUPER_ADMIN: readonly Rol[] = ['SUPER_ADMIN'];
/** Lo de todo usuario logueado, como «Cambiar mi contraseña». */
export const TODOS_LOS_ROLES: readonly Rol[] = ['DUENO', 'VENDEDOR', 'SUPER_ADMIN'];

/** El `data` de una ruta con los roles que pueden abrirla (lo lee {@link rolGuard}). */
export function conRoles(roles: readonly Rol[]): Data {
  return { roles };
}

/**
 * Control de rol de cada pantalla del shell, además del 403 del servidor (seguridad en las dos capas). La ruta declara
 * sus roles con {@link conRoles}; una ruta sin roles queda cerrada para todos, así una pantalla nueva no se abre por
 * olvido. Quien no tiene permiso, aunque escriba la URL a mano, vuelve a su inicio con «No tenés acceso a <pantalla>.»
 * (lo muestra el shell al llegar); sin sesión, o con un rol que la app no conoce (su inicio también lo rechazaría, en
 * loop), al login, sin aviso.
 */
export const rolGuard: CanActivateChildFn = (ruta) => {
  const router = inject(Router);
  const rol = inject(AuthService).sesion()?.rol;
  if (!rol || !TODOS_LOS_ROLES.includes(rol)) return router.createUrlTree(['/login']);
  const roles = ruta.data['roles'] as readonly Rol[] | undefined;
  if (roles?.includes(rol)) return true;
  inject(Avisos).dejar(`No tenés acceso a ${pantallaDe(ruta)}.`, 'advertencia');
  return router.createUrlTree(['/' + inicioDe(rol)]);
};

/** El nombre de la pantalla sale del título de la pestaña («Ventas · Ferretería» → «Ventas»). */
function pantallaDe(ruta: ActivatedRouteSnapshot): string {
  const titulo = ruta.routeConfig?.title;
  return typeof titulo === 'string' && titulo.trim() !== '' ? titulo.split(' · ')[0] : 'esa pantalla';
}
