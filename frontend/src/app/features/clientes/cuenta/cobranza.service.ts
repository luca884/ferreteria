import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CobranzaInvalida,
  CobranzaRechazada,
  CobranzaRequest,
  MotivoCobranza,
  PendientesCuenta,
  Recibo,
  ReciboCobrado,
} from './cobranza.models';

/** Genera la clave de idempotencia de un cobro. En los tests se reemplaza por una determinista. */
export const GENERAR_CLAVE_COBRO = new InjectionToken<() => string>('GENERAR_CLAVE_COBRO', {
  providedIn: 'root',
  factory: () => () => crypto.randomUUID(),
});

/** Cobranza de cuenta corriente (solo el Dueño). La ferretería y el usuario los pone el backend desde el JWT. */
@Injectable({ providedIn: 'root' })
export class CobranzaService {
  private readonly http = inject(HttpClient);

  /** Los comprobantes con deuda en orden FIFO, el saldo y lo que tiene a cuenta. Un consejo: decide el cobro, bajo lock. */
  pendientes(clienteId: number): Observable<PendientesCuenta> {
    return this.http.get<PendientesCuenta>(`${environment.apiUrl}/clientes/${clienteId}/cuenta-corriente/pendientes`);
  }

  /**
   * Registra el cobro. 201 es nuevo y 200 es la misma clave ya registrada con el mismo contenido. Los rechazos
   * (404, 409, 422) llegan como `HttpErrorResponse`; `rechazoDe` extrae su cuerpo tipado.
   */
  cobrar(pedido: CobranzaRequest): Observable<ReciboCobrado> {
    return this.http.post<Recibo>(`${environment.apiUrl}/cobranzas`, pedido, { observe: 'response' }).pipe(
      map((res) => ({ recibo: res.body as Recibo, repetida: res.status === 200 })),
    );
  }

  /** El recibo de un cobro ya hecho, para reimprimirlo: dice lo mismo que el que lo creó. */
  recibo(id: number): Observable<Recibo> {
    return this.http.get<Recibo>(`${environment.apiUrl}/recibos/${id}`);
  }
}

const MOTIVOS: readonly MotivoCobranza[] = [
  'IMPUTACION_EXCEDE_DEUDA',
  'CAJA_CERRADA',
  'CAJA_DE_OTRO_DIA',
  'CLAVE_CON_OTRO_CONTENIDO',
];

/**
 * El cuerpo de un 409 o 422 de cobranza con motivo, o null si el error es otra cosa (red, 5xx, 400, 403, 404 o el
 * 422 «El cliente está desactivado.», que no trae motivo y se muestra por su mensaje).
 */
export function rechazoDe(err: unknown): CobranzaRechazada | null {
  if (!(err instanceof HttpErrorResponse)) return null;
  if (err.status !== 409 && err.status !== 422) return null;
  const cuerpo = err.error as Partial<CobranzaRechazada> | null;
  if (typeof cuerpo !== 'object' || cuerpo === null) return null;
  if (typeof cuerpo.motivo !== 'string' || !MOTIVOS.includes(cuerpo.motivo)) return null;
  return cuerpo as CobranzaRechazada;
}

/** El cuerpo de un 400 de cobranza (`message` y, si hay, `errors` por campo), o null si no se reconoce. */
export function invalidaDe(err: unknown): CobranzaInvalida | null {
  if (!(err instanceof HttpErrorResponse) || err.status !== 400) return null;
  const cuerpo = err.error as Partial<CobranzaInvalida> | null;
  if (typeof cuerpo !== 'object' || cuerpo === null) return null;
  if (typeof cuerpo.message !== 'string') return null;
  const errors = cuerpo.errors;
  if (errors !== undefined && (typeof errors !== 'object' || errors === null)) return null;
  return cuerpo as CobranzaInvalida;
}

/**
 * Si la falla es de red o del servidor (status 0, 5xx, o un error que no es HTTP): no se sabe si el cobro quedó
 * registrado, así que se reintenta con la MISMA clave (el servidor devuelve el recibo si ya estaba).
 */
export function esTransitorio(err: unknown): boolean {
  return !(err instanceof HttpErrorResponse) || err.status === 0 || err.status >= 500;
}
