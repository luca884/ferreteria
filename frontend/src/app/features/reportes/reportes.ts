import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { fechaHoraLocal } from '../caja/caja.models';
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
  Resumen,
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
  | { tipo: 'con-descuento'; datos: Filas<ConDescuento> };

/**
 * Reportes de ventas del Dueño, como el menú Ventas de GDS: se elige el reporte y el rango «Desde / Hasta» (hoy al
 * entrar). Los totales salen del servidor; la ganancia no incluye las ventas sin costo registrado, que se avisan.
 */
@Component({
  selector: 'app-reportes',
  imports: [BarraAcciones, MontoPipe],
  templateUrl: './reportes.html',
  styleUrl: './reportes.css',
})
export class Reportes implements OnInit {
  private readonly reportes = inject(ReportesService);
  protected readonly auth = inject(AuthService);

  protected readonly opciones = REPORTES;
  protected readonly tipo = signal<TipoReporte>('resumen');
  protected readonly desde = signal(hoyEnBuenosAires());
  protected readonly hasta = signal(hoyEnBuenosAires());
  protected readonly agrupar = signal<Agrupar>('DIA');
  protected readonly resultado = signal<Resultado | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly fechaHora = fechaHoraLocal;
  protected readonly textoPeriodo = textoPeriodo;

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

  protected consultar(evento: Event): void {
    evento.preventDefault();
    this.cargar();
  }

  private cargar(): void {
    const desde = this.desde();
    const hasta = this.hasta();
    const invalido = errorDeRango(desde, hasta);
    if (invalido) {
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
        this.error.set(erroresDeCampos(err)[0] ?? mensajeDe(err, 'No se pudo cargar el reporte.'));
      },
    });
  }

  private pedir(tipo: TipoReporte, desde: string, hasta: string): Observable<Resultado> {
    if (tipo === 'resumen') {
      return conTipo(tipo, this.reportes.resumen(desde, hasta));
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
