/** Cada cuánto el mostrador vuelve a leer sus avisos de facturación (C4 y C7): un minuto, como dice la spec. */
export const INTERVALO_AVISOS_MS = 60_000;

/**
 * GET /api/facturacion/avisos-mostrador (C4, CU-16 punto 8): lo que el Dueño tiene que revisar de la facturación, para el
 * aviso del mostrador. `pendientesViejas` son las pendientes de CAE desde hace más de 30 minutos (S11); `bloqueo` es el texto
 * del bloqueo de ARCA tal cual (C4 de la rama 6), o null si la facturación no está bloqueada.
 */
export interface AvisosFacturacion {
  rechazadas: number;
  pendientesViejas: number;
  enRevision: number;
  bloqueo: string | null;
}
