import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { IconoDelMenu } from '../../layout/icono-menu';
import { OpcionMenu, gruposPara } from '../../layout/menu';
import { AvisosFacturacionService } from '../mostrador/avisos-facturacion.service';

/** Para qué sirve cada pantalla del grupo, en una línea. El Vendedor no tiene la cuenta corriente: su Clientes lo dice. */
function aclaracion(ruta: string, dueno: boolean): string {
  switch (ruta) {
    case '/presupuestos':
    case '/remitos':
      return 'Consultar y pasar a venta';
    case '/caja':
      return 'Abrir, cerrar y movimientos';
    case '/clientes':
      return dueno ? 'Alta, cuenta corriente y cobranza' : 'Buscar, alta y edición';
    case '/ventas':
      return 'Consulta, facturas y anulaciones';
    case '/reportes':
      return 'Resumen, por usuario, por artículo…';
    default:
      return '';
  }
}

/**
 * El hub «Ventas» de la barra inferior del celular (movil-sectores T2, S3): una fila por pantalla del grupo Ventas del
 * menú, en su orden. Es una vista del mismo menú filtrado por rol (`gruposPara`): no agrega pantallas ni da acceso a
 * nada que el rol no tenga hoy (el Vendedor ve Caja, Presupuestos, Remitos y Clientes; el Dueño suma Ventas y Reportes).
 *
 * La fila de Ventas lleva la pastilla con las facturas de ARCA para revisar (rechazadas, pendientes de CAE hace más de
 * 30 minutos y en revisión), lo mismo que cuenta el aviso del Mostrador. Se lee una vez al abrir y sólo para el Dueño:
 * al Vendedor el servidor le responde 403. Si la lectura falla, el hub se ve igual y sin pastilla.
 */
@Component({
  selector: 'app-hub-ventas',
  imports: [RouterLink, IconoDelMenu],
  templateUrl: './hub-ventas.html',
  styleUrl: './hub-ventas.css',
})
export class HubVentas {
  private readonly auth = inject(AuthService);

  /** Las facturas para revisar; 0 hasta la lectura y si falla. */
  protected readonly paraRevisar = signal(0);

  protected readonly filas = computed(() => {
    const dueno = this.auth.esDueno();
    const grupo = gruposPara(dueno).find((g) => g.seccion === 'ventas');
    return (grupo?.opciones ?? []).map((opcion: OpcionMenu) => ({
      ...opcion,
      aclaracion: aclaracion(opcion.ruta, dueno),
    }));
  });

  constructor() {
    if (!this.auth.esDueno()) return;
    inject(AvisosFacturacionService)
      .leer()
      .subscribe({
        next: (avisos) => this.paraRevisar.set(avisos.rechazadas + avisos.pendientesViejas + avisos.enRevision),
        error: () => this.paraRevisar.set(0),
      });
  }

  protected avisoDeFacturas(cuenta: number): string {
    return `${cuenta} ${cuenta === 1 ? 'factura' : 'facturas'} para revisar`;
  }
}
