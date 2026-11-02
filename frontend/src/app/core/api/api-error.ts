import { HttpErrorResponse } from '@angular/common/http';

/**
 * Mensajes por campo de un 400 de validación del backend (`{ message, errors: { campo: mensaje } }`),
 * en el orden en que vienen. Lista vacía si el error no es HTTP o no trae `errors`: en ese caso la
 * pantalla muestra el `message` genérico.
 */
export function erroresDeCampos(err: unknown): string[] {
  return Object.values(erroresPorCampo(err));
}

/**
 * Los mismos mensajes que {@link erroresDeCampos}, con el nombre de su campo (`{ numeroDocumento: '…' }`), para
 * mostrar cada uno debajo de su campo. Objeto vacío si el error no es HTTP o no trae `errors`.
 */
export function erroresPorCampo(err: unknown): Record<string, string> {
  if (!(err instanceof HttpErrorResponse)) return {};
  const errors: unknown = err.error?.errors;
  if (errors === null || typeof errors !== 'object') return {};
  return Object.fromEntries(
    Object.entries(errors).filter(
      (par): par is [string, string] => typeof par[1] === 'string' && par[1].trim() !== '',
    ),
  );
}

/** El `message` que manda el backend (409, 403…); `porDefecto` si el error no es HTTP o no trae mensaje. */
export function mensajeDe(err: unknown, porDefecto: string): string {
  const mensaje: unknown = err instanceof HttpErrorResponse ? err.error?.message : undefined;
  return typeof mensaje === 'string' && mensaje.trim() !== '' ? mensaje : porDefecto;
}

/** 503: el servidor agotó los reintentos por contención («El sistema está ocupado, reintentá.»). No quedó nada hecho. */
export function esOcupado(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 503;
}
