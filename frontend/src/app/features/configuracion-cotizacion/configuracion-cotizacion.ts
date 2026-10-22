import { Component, OnInit, inject, signal } from '@angular/core';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { RecalculoCotizacion } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { formatearCotizacion } from '../../core/formato/monto';
import { formatearFechaHora } from '../../core/formato/fecha-hora';
import { BarraAcciones } from '../../shared/barra-acciones';
import {
  ConfiguracionCotizacion,
  ConfiguracionCotizacionRequest,
  RecalculoConAvisos,
} from './configuracion-cotizacion.models';
import { ConfiguracionCotizacionService } from './configuracion-cotizacion.service';
import { FormularioCotizacion } from './formulario-cotizacion';

/**
 * Configuración › Cotización (solo el Dueño). Contenedor: habla con los servicios y decide qué mensajes mostrar; el
 * formulario es presentacional. Guardar no recalcula nada: el cambio rige desde el próximo recálculo, y «Recalcular
 * ahora» está al lado para hacerlo en el momento.
 */
@Component({
  selector: 'app-configuracion-cotizacion',
  imports: [BarraAcciones, FormularioCotizacion],
  templateUrl: './configuracion-cotizacion.html',
  styleUrl: './configuracion-cotizacion.css',
})
export class PaginaConfiguracionCotizacion implements OnInit {
  private readonly configuraciones = inject(ConfiguracionCotizacionService);
  private readonly cotizaciones = inject(CotizacionService);
  protected readonly auth = inject(AuthService);

  protected readonly configuracion = signal<ConfiguracionCotizacion | null>(null);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly guardando = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Lo que el backend rechazó al guardar, un mensaje por campo (400 de validación). */
  protected readonly erroresGuardado = signal<string[]>([]);
  /** Cualquier otro error al guardar (403, 409, sin conexión…). */
  protected readonly errorGuardado = signal<string | null>(null);
  protected readonly recalculando = signal(false);
  protected readonly resultado = signal<string | null>(null);
  /** Artículos que conservaron su precio en el último recálculo: sus ajustes lo dejarían en 0 o menos. */
  protected readonly avisosSalteados = signal<string[]>([]);
  protected readonly errorRecalculo = signal<string | null>(null);
  /** El formulario difiere de lo guardado: «Recalcular ahora» usa lo guardado, así que espera. */
  protected readonly hayCambios = signal(false);

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.configuraciones.obtener().subscribe({
      next: (configuracion) => {
        this.configuracion.set(configuracion);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  protected guardar(pedido: ConfiguracionCotizacionRequest): void {
    if (this.guardando()) return;
    this.guardando.set(true);
    this.aviso.set(null);
    this.erroresGuardado.set([]);
    this.errorGuardado.set(null);
    this.configuraciones.guardar(pedido).subscribe({
      next: (guardada) => {
        this.guardando.set(false);
        this.configuracion.set(guardada);
        this.aviso.set('Se guardó la configuración.');
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const campos = erroresDeCampos(err);
        if (campos.length > 0) this.erroresGuardado.set(campos);
        else this.errorGuardado.set(mensajeDe(err, 'No se pudo guardar la configuración.'));
      },
    });
  }

  /** `confirmaDesvio`: el botón «Confirmar» del aviso de un dólar que se aleja más del 20 % del vigente. */
  protected recalcular(confirmaDesvio = false): void {
    if (this.recalculando() || this.motivoSinRecalculo() !== null) return;
    this.recalculando.set(true);
    this.resultado.set(null);
    this.avisosSalteados.set([]);
    this.errorRecalculo.set(null);
    this.cotizaciones.recalcular(confirmaDesvio).subscribe({
      next: (recalculo: RecalculoConAvisos) => {
        this.recalculando.set(false);
        this.resultado.set(describirRecalculo(recalculo));
        this.avisosSalteados.set(recalculo.avisos ?? []);
        // El dólar vigente cambió: se vuelve a leer para mostrarlo (sin tapar el formulario si falla).
        this.configuraciones.obtener().subscribe({ next: (c) => this.configuracion.set(c), error: () => undefined });
      },
      error: (err: unknown) => {
        this.recalculando.set(false);
        this.errorRecalculo.set(mensajeDe(err, 'No se pudieron recalcular los precios.'));
      },
    });
  }

  /** Por qué «Recalcular ahora» no se puede usar, para decirlo al lado del botón; null si se puede. */
  protected motivoSinRecalculo(): string | null {
    const c = this.configuracion();
    if (c && !c.activa) return 'La cotización está apagada. Activala y guardá para poder recalcular.';
    if (this.hayCambios()) return 'Guardá los cambios antes de recalcular.';
    return null;
  }

  protected textoVigente(c: ConfiguracionCotizacion): string {
    if (c.vigente === null) return 'Todavía no se calcularon precios con el dólar de esta ferretería.';
    const desde = formatearFechaHora(c.vigenteDesde);
    const base = `Los precios en dólares se calcularon con el dólar a ${formatearCotizacion(c.vigente)}`;
    return desde ? `${base} (${desde}).` : `${base}.`;
  }
}

function describirRecalculo(r: RecalculoCotizacion): string {
  const dolar = formatearCotizacion(r.cotizacion);
  if (r.articulosRecalculados === 0) return `Ningún artículo cambió de precio con el dólar a ${dolar}.`;
  return r.articulosRecalculados === 1
    ? `Se recalculó 1 artículo con el dólar a ${dolar}.`
    : `Se recalcularon ${r.articulosRecalculados} artículos con el dólar a ${dolar}.`;
}
