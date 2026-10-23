import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ConfiguracionVentasService } from './configuracion-ventas.service';

/**
 * Configuración › Consumidor Final (solo el Dueño), como el «Límite de Venta a Cons.Final sin datos» de GDS (G88,
 * GDS-32): desde este importe la venta facturable necesita un cliente con DNI, CUIT o CUIL.
 */
@Component({
  selector: 'app-configuracion-ventas',
  imports: [ReactiveFormsModule, BarraAcciones],
  templateUrl: './configuracion-ventas.html',
  styleUrl: './configuracion-ventas.css',
})
export class PaginaConfiguracionVentas implements OnInit {
  private readonly service = inject(ConfiguracionVentasService);
  protected readonly auth = inject(AuthService);

  protected readonly form = inject(FormBuilder).group({
    topeConsumidorFinal: [null as number | null, [Validators.required, Validators.min(0.01)]],
  });

  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly guardando = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Lo que el backend rechazó, un mensaje por campo (400 de validación). */
  protected readonly erroresGuardado = signal<string[]>([]);
  protected readonly errorGuardado = signal<string | null>(null);

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.service.obtener().subscribe({
      next: (configuracion) => {
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
    this.aviso.set(null);
    this.erroresGuardado.set([]);
    this.errorGuardado.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.guardando.set(true);
    this.service.guardar({ topeConsumidorFinal: this.form.getRawValue().topeConsumidorFinal! }).subscribe({
      next: (guardada) => {
        this.guardando.set(false);
        this.form.reset(guardada);
        this.aviso.set('Se guardó el tope.');
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const campos = erroresDeCampos(err);
        if (campos.length > 0) this.erroresGuardado.set(campos);
        else this.errorGuardado.set(mensajeDe(err, 'No se pudo guardar la configuración.'));
      },
    });
  }

  protected invalido(): boolean {
    const control = this.form.controls.topeConsumidorFinal;
    return control.touched && control.invalid;
  }
}
