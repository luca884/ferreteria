import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { formatearCosto, formatearMonto } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ALICUOTA_IVA_LABELS } from '../articulos/articulo.models';
import { ArticuloEnControl, CONTROLES, ControlDeDatos } from './controles-de-datos.models';
import { ControlesDeDatosService } from './controles-de-datos.service';

const MENSAJE_ERROR = 'No se pudo cargar el listado.';

const FORMATO_EMITIDO = new Intl.DateTimeFormat('es-AR', {
  timeZone: 'America/Argentina/Buenos_Aires',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** «23/10/2026 12:30»: el instante dicho en hora de Buenos Aires, que es la del negocio. */
function emitidoEl(ahora: Date): string {
  const p = Object.fromEntries(FORMATO_EMITIDO.formatToParts(ahora).map((parte) => [parte.type, parte.value]));
  return `${p['day']}/${p['month']}/${p['year']} ${p['hour']}:${p['minute']}`;
}

/**
 * Controles de datos del catálogo (GDS-18), como los de GDS (G81): se elige un control, se ven los artículos mal cargados
 * y cada uno lleva a su ficha para corregirlo; el listado se imprime. Solo el Dueño: muestra el costo. Al imprimir solo
 * sale `.controles-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-controles-de-datos',
  imports: [BarraAcciones, RouterLink],
  templateUrl: './controles-de-datos.html',
  styleUrl: './controles-de-datos.css',
})
export class ControlesDeDatos implements OnInit {
  private readonly api = inject(ControlesDeDatosService);
  protected readonly auth = inject(AuthService);

  protected readonly controles = CONTROLES;
  protected readonly control = signal<ControlDeDatos>('COSTO_CERO');
  protected readonly filas = signal<ArticuloEnControl[] | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly emitido = signal(emitidoEl(new Date()));

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly elegido = computed(() => this.controles.find((c) => c.valor === this.control())!);

  protected readonly costo = (f: ArticuloEnControl) => formatearCosto(f.costo, f.moneda);
  protected readonly precio = (f: ArticuloEnControl) => formatearMonto(f.precioFinal);
  protected readonly iva = (f: ArticuloEnControl) => ALICUOTA_IVA_LABELS[f.alicuotaIva];

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected elegir(valor: string): void {
    this.control.set(valor as ControlDeDatos);
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar(this.control()).subscribe({
      next: (filas) => {
        this.filas.set(filas);
        this.emitido.set(emitidoEl(new Date()));
        this.cargando.set(false);
      },
      error: () => {
        this.filas.set(null);
        this.cargando.set(false);
        this.error.set(MENSAJE_ERROR);
      },
    });
  }

  protected imprimir(): void {
    window.print();
  }
}
