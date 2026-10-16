import { HttpErrorResponse } from '@angular/common/http';

/**
 * Mensajes por campo de un 400 de validación del backend (`{ message, errors: { campo: mensaje } }`),
 * en el orden en que vienen. Lista vacía si el error no es HTTP o no trae `errors`: en ese caso la
 * pantalla muestra el `message` genérico.
 */
export function erroresDeCampos(err: unknown): string[] {
  if (!(err instanceof HttpErrorResponse)) return [];
  const errors: unknown = err.error?.errors;
  if (errors === null || typeof errors !== 'object') return [];
  return Object.values(errors).filter(
    (mensaje): mensaje is string => typeof mensaje === 'string' && mensaje.trim() !== '',
  );
}

/** El `message` que manda el backend (409, 403…); `porDefecto` si el error no es HTTP o no trae mensaje. */
export function mensajeDe(err: unknown, porDefecto: string): string {
  const mensaje: unknown = err instanceof HttpErrorResponse ? err.error?.message : undefined;
  return typeof mensaje === 'string' && mensaje.trim() !== '' ? mensaje : porDefecto;
}
