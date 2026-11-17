import { Component, computed, effect, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { EMPTY, catchError, interval, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { FiltroFiscal } from '../ventas/ventas.models';
import { AvisosFacturacion as Avisos, INTERVALO_AVISOS_MS } from './avisos-facturacion.models';
import { AvisosFacturacionService } from './avisos-facturacion.service';

/** Una cuenta del aviso, con la bandeja de Ventas a la que lleva. */
interface Cuenta {
  clave: string;
  texto: string;
  fiscal: FiltroFiscal;
}

/** «1 rechazada», «3 rechazadas». */
function plural(n: number, singular: string, varias: string): string {
  return `${n} ${n === 1 ? singular : varias}`;
}

/** Las cuentas del aviso que no están en cero, cada una con la bandeja de Ventas a la que lleva. */
function cuentasDe(a: Avisos | null): Cuenta[] {
  if (!a) return [];
  const todas: (Cuenta | null)[] = [
    a.rechazadas > 0
      ? { clave: 'rechazadas', texto: plural(a.rechazadas, 'rechazada', 'rechazadas'), fiscal: 'RECHAZADAS' }
      : null,
    a.pendientesViejas > 0
      ? {
          clave: 'pendientes',
          texto: `${plural(a.pendientesViejas, 'pendiente', 'pendientes')} de CAE desde hace más de 30 minutos`,
          fiscal: 'PENDIENTES',
        }
      : null,
    // Las que están en revisión salen en la bandeja de pendientes (C1).
    a.enRevision > 0 ? { clave: 'revision', texto: `${a.enRevision} en revisión`, fiscal: 'PENDIENTES' } : null,
  ];
  return todas.filter((c): c is Cuenta => c !== null);
}

/**
 * Los avisos de facturación del mostrador del Dueño (CU-16 punto 8, C4): cuántas ventas hay rechazadas, pendientes de CAE
 * desde hace más de 30 minutos (S11) y en revisión, cada una con su enlace a la bandeja de Ventas, y si ARCA bloqueó la
 * facturación. Se leen al abrir y cada minuto, y solo se muestra lo que no es cero. No frena nada: va debajo del buscador.
 * Una lectura que falla deja lo último que se leyó. Al Vendedor no se le pide (el servidor le daría 403).
 */
@Component({
  selector: 'app-avisos-facturacion',
  imports: [RouterLink],
  templateUrl: './avisos-facturacion.html',
  styleUrl: './avisos-facturacion.css',
})
export class AvisosFacturacion {
  private readonly auth = inject(AuthService);
  private readonly service = inject(AvisosFacturacionService);

  /** null hasta la primera lectura. */
  private readonly avisos = signal<Avisos | null>(null);

  protected readonly cuentas = computed<Cuenta[]>(() => cuentasDe(this.avisos()));
  protected readonly bloqueo = computed(() => this.avisos()?.bloqueo ?? null);
  /** Cuántos avisos hay: cada cuenta y el bloqueo de la facturación, uno cada uno. */
  private readonly cantidadDeAvisos = computed(
    () => this.cuentas().length + (this.bloqueo() === null ? 0 : 1),
  );
  /**
   * La cuenta de avisos, informada al mostrador: la franja del celular (móvil-pulgar, T4) suma este número a los avisos
   * que sabe ella. Se informa después de la detección de cambios, cuando el mostrador ya escucha la salida.
   */
  readonly cantidad = output<number>();

  constructor() {
    effect(() => this.cantidad.emit(this.cantidadDeAvisos()));
    if (this.auth.sesion()?.rol !== 'DUENO') return;
    interval(INTERVALO_AVISOS_MS)
      .pipe(
        startWith(0),
        // Una lectura nueva deja sin efecto a la anterior que siga en camino; un error no corta las siguientes.
        switchMap(() => this.service.leer().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((avisos) => this.avisos.set(avisos));
  }
}
