import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { RecalculoCotizacion, VistaPreviaRecalculo } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { formatearCotizacion } from '../../core/formato/monto';
import { formatearFechaHora } from '../../core/formato/fecha-hora';
import { BarraAcciones } from '../../shared/barra-acciones';
import { esCelular } from '../../shared/es-celular';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import {
  ConfiguracionCotizacion,
  ConfiguracionCotizacionRequest,
} from './configuracion-cotizacion.models';
import { ConfiguracionCotizacionService } from './configuracion-cotizacion.service';
import { FormularioCotizacion } from './formulario-cotizacion';
import { VistaPreviaRecalculoComponent } from './vista-previa-recalculo';

/**
 * Configuración › Cotización (solo el Dueño). Contenedor: habla con los servicios y decide qué mensajes mostrar; el
 * formulario es presentacional. Guardar no recalcula nada: el cambio rige desde el próximo recálculo, y «Recalcular
 * ahora» está al lado para hacerlo en el momento.
 */
@Component({
  selector: 'app-configuracion-cotizacion',
  imports: [BarraAcciones, FormularioCotizacion, VistaPreviaRecalculoComponent],
  templateUrl: './configuracion-cotizacion.html',
  styleUrl: './configuracion-cotizacion.css',
})
export class PaginaConfiguracionCotizacion implements OnInit {
  private readonly configuraciones = inject(ConfiguracionCotizacionService);
  private readonly cotizaciones = inject(CotizacionService);
  protected readonly auth = inject(AuthService);
  protected readonly celular = esCelular();
  private readonly router = this.celular ? inject(Router) : null;

  protected readonly configuracion = signal<ConfiguracionCotizacion | null>(null);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly guardando = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Lo que el backend rechazó al guardar, un mensaje por campo (400 de validación). */
  protected readonly erroresGuardado = signal<string[]>([]);
  /** Cualquier otro error al guardar (403, 409, sin conexión…). */
  protected readonly errorGuardado = signal<string | null>(null);
  /** Se está pidiendo la vista previa de «Recalcular ahora». */
  protected readonly preparando = signal(false);
  /** Cómo quedarían los precios en dólares; null mientras no se pidió o después de aplicar o cancelar. */
  protected readonly vistaPrevia = signal<VistaPreviaRecalculo | null>(null);
  protected readonly recalculando = signal(false);
  protected readonly resultado = signal<string | null>(null);
  /** Artículos que conservaron su precio en el último recálculo: sus ajustes lo dejarían en 0 o menos. */
  protected readonly avisosSalteados = signal<string[]>([]);
  protected readonly errorRecalculo = signal<string | null>(null);
  /** El formulario difiere de lo guardado: «Recalcular ahora» usa lo guardado, así que espera. */
  protected readonly hayCambios = signal(false);
  /** Por qué «Ver vista previa» y «Recalcular ahora» no se pueden usar sin una regla de negocio de por medio (S5). */
  protected readonly motivos = computed<Motivos<'recalcular'>>(() => ({ recalcular: this.motivoSinRecalculo() }));
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  // Igual que Margen y redondeo: salir descarta la edición y vuelve a Artículos, sin guardar.
  protected salir(): void {
    if (!this.guardando()) void this.router?.navigateByUrl('/articulos');
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

  /**
   * «Recalcular ahora» y «Ver vista previa» del aviso del dólar: primero se muestra cómo quedarían los precios, sin
   * cambiar nada. Recién «Aplicar» recalcula (mejoras-ferreteros-bloque-1, S2).
   */
  protected pedirVistaPrevia(): void {
    // S5: con un motivo de negocio el botón avisa por qué en vez de quedar apagado sin explicación.
    if (this.motivo.mostrar('recalcular')) return;
    if (this.ocupado()) return;
    this.motivo.limpiar();
    this.preparando.set(true);
    this.vistaPrevia.set(null);
    this.resultado.set(null);
    this.avisosSalteados.set([]);
    this.errorRecalculo.set(null);
    this.cotizaciones.vistaPrevia().subscribe({
      next: (vista) => {
        this.preparando.set(false);
        this.vistaPrevia.set(vista);
      },
      error: (err: unknown) => {
        this.preparando.set(false);
        this.errorRecalculo.set(mensajeDe(err, 'No se pudo preparar la vista previa.'));
      },
    });
  }

  /** Aplica la vista previa con el dólar que se vio; si supera el aviso, lo confirma. */
  protected aplicar(): void {
    const vista = this.vistaPrevia();
    if (vista === null || this.recalculando()) return;
    this.recalculando.set(true);
    this.errorRecalculo.set(null);
    this.cotizaciones.recalcular(vista.superaUmbral, vista.dolar).subscribe({
      next: (recalculo: RecalculoCotizacion) => {
        this.recalculando.set(false);
        this.vistaPrevia.set(null);
        this.resultado.set(describirRecalculo(recalculo));
        this.avisosSalteados.set(recalculo.avisos ?? []);
        // El dólar vigente cambió: se vuelve a leer para mostrarlo (sin tapar el formulario si falla).
        this.configuraciones.obtener().subscribe({ next: (c) => this.configuracion.set(c), error: () => undefined });
      },
      error: (err: unknown) => {
        this.recalculando.set(false);
        // Lo que se veía ya no vale (por ejemplo, el dólar cambió): se cierra y se vuelve a pedir.
        this.vistaPrevia.set(null);
        this.errorRecalculo.set(mensajeDe(err, 'No se pudieron recalcular los precios.'));
      },
    });
  }

  protected cancelarVistaPrevia(): void {
    if (!this.recalculando()) this.vistaPrevia.set(null);
  }

  protected ocupado(): boolean {
    return this.preparando() || this.recalculando();
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
    // Sin dólar fijado, la importación calcula con el último publicado: el back lo manda como vigente, sin fecha.
    if (c.vigenteDesde === null) {
      return `Los precios en dólares se calculan con el último dólar publicado, a ${formatearCotizacion(c.vigente)}.`;
    }
    // La fecha es la del último recálculo aplicado, no la del dato de la fuente (esa va en el menú).
    const desde = formatearFechaHora(c.vigenteDesde);
    const dolar = `con el dólar a ${formatearCotizacion(c.vigente)}`;
    return desde
      ? `Los precios en dólares se recalcularon el ${desde} ${dolar}.`
      : `Los precios en dólares se recalcularon ${dolar}.`;
  }

  /** Cuándo se recalculan solos los precios en dólares (lo guardado, no lo que se está editando). */
  protected textoRecalculo(c: ConfiguracionCotizacion): string {
    if (!c.activa) return 'Con la cotización apagada, los precios en dólares no se recalculan solos.';
    return (
      `Los precios en dólares se recalculan solos todos los días a las ${c.horaRecalculo}, también después de ` +
      'cambiar el dólar o el valor que usan. Si no querés esperar, podés recalcular ahora: antes de cambiar nada ' +
      'vas a ver cómo quedan los precios.'
    );
  }
}

function describirRecalculo(r: RecalculoCotizacion): string {
  const dolar = formatearCotizacion(r.cotizacion);
  if (r.articulosRecalculados === 0) return `Ningún artículo cambió de precio con el dólar a ${dolar}.`;
  return r.articulosRecalculados === 1
    ? `Se recalculó 1 artículo con el dólar a ${dolar}.`
    : `Se recalcularon ${r.articulosRecalculados} artículos con el dólar a ${dolar}.`;
}
