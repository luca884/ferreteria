import { Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { IconoDelMenu } from '../../layout/icono-menu';
import { MenuActual } from '../../layout/menu-actual';
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
 * El hub de un grupo de la barra inferior (CU-17, S5): una fila por opción visible, en el orden de `MenuActual`.
 * No agrega pantallas ni da permisos. La URL histórica `/area-ventas` abre el grupo Ventas.
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
  private readonly menu = inject(MenuActual);

  private readonly avisos = inject(AvisosFacturacionService);
  private readonly parametros = toSignal(inject(ActivatedRoute).paramMap, { requireSync: true });
  protected readonly grupo = computed(() => this.menu.grupoDelHub(this.parametros().get('grupo')));

  protected readonly filas = computed(() => {
    const dueno = this.auth.esDueno();
    return (this.grupo()?.opciones ?? []).map((opcion) => ({
      ...opcion,
      aclaracion: aclaracion(opcion.ruta, dueno),
    }));
  });

  private readonly muestraVentas = computed(() =>
    this.auth.esDueno() && !!this.grupo()?.opciones.some((opcion) => opcion.ruta === '/ventas'),
  );
  /** Las facturas para revisar; 0 hasta la lectura y si falla. */
  protected readonly paraRevisar = toSignal(toObservable(this.muestraVentas).pipe(
    switchMap((visible) => visible ? this.avisos.leer().pipe(
      map((avisos) => avisos.rechazadas + avisos.pendientesViejas + avisos.enRevision),
      catchError(() => of(0)),
    ) : of(0)),
  ), { initialValue: 0 });

  protected avisoDeFacturas(cuenta: number): string {
    return `${cuenta} ${cuenta === 1 ? 'factura' : 'facturas'} para revisar`;
  }
}
