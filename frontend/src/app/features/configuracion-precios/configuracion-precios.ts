import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { ConfiguracionPrecios } from '../articulos/articulo.models';
import { ConfiguracionPreciosGuardada, ConfiguracionPreciosService } from './configuracion-precios.service';

/** Cambio pendiente de confirmar: cuántos artículos cambian de precio (null si no se pudo contar). */
interface CambioPendiente {
  nueva: ConfiguracionPrecios;
  articulos: number | null;
}

/**
 * Configuración › Margen y redondeo (solo el Dueño), como la ganancia por defecto y el redondeo de GDS. Guardar
 * recalcula los precios que dependen de estos valores, así que antes se avisa cuántos artículos cambian.
 */
@Component({
  selector: 'app-configuracion-precios',
  imports: [ReactiveFormsModule, BarraAcciones, Confirmacion],
  templateUrl: './configuracion-precios.html',
  styleUrl: './configuracion-precios.css',
})
export class PaginaConfiguracionPrecios implements OnInit {
  private readonly service = inject(ConfiguracionPreciosService);
  protected readonly auth = inject(AuthService);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    margenDefectoPorcentaje: [0, [Validators.required, Validators.min(0), Validators.max(1000)]],
    redondeoMultiplo: [0, [Validators.required, Validators.min(0), Validators.max(1000)]],
  });

  protected readonly guardada = signal<ConfiguracionPrecios | null>(null);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly guardando = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Artículos que conservaron su precio: sus ajustes lo dejarían en 0 o menos. */
  protected readonly avisosSalteados = signal<string[]>([]);
  /** Lo que el backend rechazó, un mensaje por campo (400 de validación). */
  protected readonly erroresGuardado = signal<string[]>([]);
  /** Cualquier otro error al guardar (409 sin cotización, sin conexión…). */
  protected readonly errorGuardado = signal<string | null>(null);
  protected readonly cambioPendiente = signal<CambioPendiente | null>(null);

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.obtener().subscribe({
      next: (configuracion) => {
        this.guardada.set(configuracion);
        this.form.reset(configuracion);
        this.cargando.set(false);
      },
      error: () => {
        this.cargando.set(false);
        this.cargaFallida.set(true);
      },
    });
  }

  protected guardar(): void {
    if (this.guardando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.limpiarMensajes();
    const nueva = this.form.getRawValue();
    const anterior = this.guardada();
    if (
      anterior !== null &&
      anterior.margenDefectoPorcentaje === nueva.margenDefectoPorcentaje &&
      anterior.redondeoMultiplo === nueva.redondeoMultiplo
    ) {
      this.enviar(nueva);
      return;
    }
    // Cambia algún valor: se recalculan precios. Se avisa cuántos antes, como con el margen de un rubro.
    this.guardando.set(true);
    this.service.articulosQueCambian(nueva).subscribe({
      next: (articulos) => {
        this.guardando.set(false);
        if (articulos === 0) this.enviar(nueva);
        else this.cambioPendiente.set({ nueva, articulos });
      },
      error: () => {
        this.guardando.set(false);
        this.cambioPendiente.set({ nueva, articulos: null });
      },
    });
  }

  protected confirmarCambio(): void {
    const cambio = this.cambioPendiente();
    this.cambioPendiente.set(null);
    if (cambio) this.enviar(cambio.nueva);
  }

  protected mensajeCambio(cambio: CambioPendiente): string {
    if (cambio.articulos === null) return 'Se recalcula el precio de los artículos que dependen de estos valores.';
    return cambio.articulos === 1
      ? 'Se recalcula el precio de 1 artículo.'
      : `Se recalcula el precio de ${cambio.articulos} artículos.`;
  }

  protected invalido(campo: 'margenDefectoPorcentaje' | 'redondeoMultiplo'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  private enviar(nueva: ConfiguracionPrecios): void {
    this.guardando.set(true);
    this.service.guardar(nueva).subscribe({
      next: (resultado) => {
        this.guardando.set(false);
        const configuracion = {
          margenDefectoPorcentaje: resultado.margenDefectoPorcentaje,
          redondeoMultiplo: resultado.redondeoMultiplo,
        };
        this.guardada.set(configuracion);
        this.form.reset(configuracion);
        this.aviso.set(describirGuardado(resultado));
        this.avisosSalteados.set(resultado.avisos ?? []);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const campos = erroresDeCampos(err);
        if (campos.length > 0) this.erroresGuardado.set(campos);
        else this.errorGuardado.set(mensajeDe(err, 'No se pudo guardar la configuración.'));
      },
    });
  }

  private limpiarMensajes(): void {
    this.aviso.set(null);
    this.avisosSalteados.set([]);
    this.erroresGuardado.set([]);
    this.errorGuardado.set(null);
  }
}

function describirGuardado(r: ConfiguracionPreciosGuardada): string {
  if (r.articulosRecalculados === 0) return 'Se guardó la configuración.';
  return r.articulosRecalculados === 1
    ? 'Se guardó la configuración. Se recalculó 1 precio.'
    : `Se guardó la configuración. Se recalcularon ${r.articulosRecalculados} precios.`;
}
