import { InjectionToken } from '@angular/core';
import { Sesion } from './auth.models';

/**
 * Pasos que corren al cerrar la sesión (a mano o por un 401), con la sesión que se cierra, antes de borrarla. Cada
 * pantalla que guarda algo del usuario en el navegador suma el suyo en `app.config.ts` (`multi: true`), así `core` no
 * conoce a las pantallas. Un paso que falla no frena el cierre.
 */
export const AL_CERRAR_SESION = new InjectionToken<ReadonlyArray<(sesion: Sesion) => void>>('AL_CERRAR_SESION');
