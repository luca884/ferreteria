import { ChangeDetectorRef, Component, OnInit, computed, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { BarraAcciones } from '../../shared/barra-acciones';
import { emitidoEl } from '../../shared/emitido';
import { TIPOS_MOVIMIENTO, TIPO_MOVIMIENTO_LABELS, TipoMovimientoCaja, fechaHoraLocal } from '../caja/caja.models';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import {
  Agrupar,
  ConDescuento,
  Filas,
  PorArticulo,
  PorMedioPago,
  PorPeriodo,
  PorRubro,
  PorUsuario,
  REPORTES,
  ReporteEnFilas,
  ReporteMovimientos,
  Resumen,
  TIPO_MOVIMIENTO_PLURAL,
  TipoReporte,
  avisoSinCosto,
  errorDeRango,
  hoyEnBuenosAires,
  textoPeriodo,
} from './reportes.models';
import { ReportesService } from './reportes.service';

/** Lo que se está mostrando: el reporte que se pidió y su respuesta. */
type Resultado =
  | { tipo: 'resumen'; datos: Resumen }
  | { tipo: 'por-usuario'; datos: Filas<PorUsuario> }
  | { tipo: 'por-medio-pago'; datos: Filas<PorMedioPago> }
  | { tipo: 'por-periodo'; datos: Filas<PorPeriodo> }
  | { tipo: 'por-articulo'; datos: Filas<PorArticulo> }
  | { tipo: 'por-rubro'; datos: Filas<PorRubro> }
  | { tipo: 'con-descuento'; datos: Filas<ConDescuento> }
  | { tipo: 'movimientos-caja'; datos: ReporteMovimientos; filtro: TipoMovimientoCaja | null };

/**
 * Reportes del Dueño: los de ventas, como el menú Ventas de GDS, y el de ingresos, gastos y retiros de la caja (G73). Se
 * elige el reporte y el rango «Desde / Hasta» (hoy al entrar). Los totales salen del servidor; la ganancia no incluye
 * las ventas sin costo registrado, que se avisan. El de la caja se imprime: al imprimir solo sale `.caja-imprimible`
 * (reglas `@media print` en styles.css), con el rango y el tipo del resultado y no los del formulario.
 */
@Component({
  selector: 'app-reportes',
  imports: [BarraAcciones, MontoPipe],
  templateUrl: './reportes.html',
  styleUrl: './reportes.css',
})
export class Reportes implements OnInit {
  private readonly reportes = inject(ReportesService);
  private readonly vista = inject(ChangeDetectorRef);
  protected readonly auth = inject(AuthService);

  protected readonly opciones = REPORTES;
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly tipo = signal<TipoReporte>('resumen');
  protected readonly desde = signal(hoyEnBuenosAires());
  protected readonly hasta = signal(hoyEnBuenosAires());
  protected readonly agrupar = signal<Agrupar>('DIA');
  protected readonly tipoMovimiento = signal<TipoMovimientoCaja | ''>('');
  protected readonly resultado = signal<Resultado | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly fechaHora = fechaHoraLocal;
  protected readonly fechaCompleta = fechaCompletaLocal;
  protected readonly textoPeriodo = textoPeriodo;
  protected readonly tiposMovimiento = TIPOS_MOVIMIENTO;
  protected readonly plural = TIPO_MOVIMIENTO_PLURAL;
  protected readonly tipoLabels = TIPO_MOVIMIENTO_LABELS;
  protected readonly emitido = signal(emitidoEl(new Date()));
  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);

  /** Los totales de la hoja: los tres con «Todos»; con un tipo, solo el de ese tipo. */
  protected readonly totalesDeCaja = computed(() => {
    const r = this.resultado();
    if (r?.tipo !== 'movimientos-caja') return [];
    return TIPOS_MOVIMIENTO.filter((t) => !r.filtro || t === r.filtro).map((t) => ({
      tipo: t,
      texto: TIPO_MOVIMIENTO_PLURAL[t],
      total: r.datos.totales[t],
    }));
  });

  /**
   * La fila de totales de «Por artículo» (informacion-y-links, S6): total y ganancia sumados en centavos; la cantidad no,
   * porque mezcla unidades. Null en los otros reportes.
   */
  protected readonly totalesPorArticulo = computed(() => {
    const r = this.resultado();
    if (r?.tipo !== 'por-articulo') return null;
    const enCentavos = (valores: number[]) => sumar(valores.map((v) => Math.round(v * 100))) / 100;
    return {
      total: enCentavos(r.datos.filas.map((f) => f.total)),
      ganancia: enCentavos(r.datos.filas.map((f) => f.ganancia)),
    };
  });

  /** El aviso de las ventas o renglones sin costo del reporte que se muestra. */
  protected readonly avisoCosto = computed(() => {
    const r = this.resultado();
    if (!r) return null;
    switch (r.tipo) {
      case 'resumen':
        return avisoSinCosto(r.datos.ventasSinCosto, 'ventas');
      case 'por-usuario':
      case 'por-periodo':
        return avisoSinCosto(sumar(r.datos.filas.map((f) => f.ventasSinCosto)), 'ventas');
      case 'por-articulo':
      case 'por-rubro':
        return avisoSinCosto(sumar(r.datos.filas.map((f) => f.renglonesSinCosto)), 'renglones');
      default:
        return null;
    }
  });

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  /** «Emitido el» es la hora de imprimir, no la de ver el reporte: se renueva antes de abrir el diálogo. */
  protected imprimir(): void {
    this.emitido.set(emitidoEl(new Date()));
    this.vista.detectChanges();
    window.print();
  }

  protected consultar(evento: Event): void {
    evento.preventDefault();
    this.cargar();
  }

  private cargar(): void {
    const desde = this.desde();
    const hasta = this.hasta();
    const invalido = errorDeRango(desde, hasta);
    if (invalido) {
      // Un error no deja abajo el resultado de la consulta anterior, ni su hoja de caja (bugs-relevamiento-celular, S3).
      this.resultado.set(null);
      this.error.set(invalido);
      return;
    }
    this.error.set(null);
    this.cargando.set(true);
    const tipo = this.tipo();
    this.pedir(tipo, desde, hasta).subscribe({
      next: (resultado) => {
        this.resultado.set(resultado);
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.cargando.set(false);
        this.resultado.set(null);
        this.error.set(erroresDeCampos(err)[0] ?? mensajeDe(err, 'No se pudo cargar el reporte.'));
      },
    });
  }

  private pedir(tipo: TipoReporte, desde: string, hasta: string): Observable<Resultado> {
    if (tipo === 'resumen') {
      return conTipo(tipo, this.reportes.resumen(desde, hasta));
    }
    if (tipo === 'movimientos-caja') {
      const filtro = this.tipoMovimiento() || null;
      return this.reportes
        .movimientosCaja(desde, hasta, filtro ?? undefined)
        .pipe(map((datos) => ({ tipo, datos, filtro }) as Resultado));
    }
    const agrupar = tipo === 'por-periodo' ? this.agrupar() : undefined;
    return conTipo(tipo, this.reportes.filas(tipo as ReporteEnFilas, desde, hasta, agrupar));
  }
}

function conTipo(tipo: TipoReporte, pedido: Observable<unknown>): Observable<Resultado> {
  return pedido.pipe(map((datos) => ({ tipo, datos }) as Resultado));
}

function sumar(valores: number[]): number {
  return valores.reduce((total, valor) => total + valor, 0);
}
