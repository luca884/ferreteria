import { Component, OnInit, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import {
  CONDICIONES_IVA_FERRETERIA,
  CONDICION_IVA_FERRETERIA_LABELS,
  CondicionIvaFerreteria,
} from '../admin-ferreterias/ferreteria-admin.models';
import { hoyEnBuenosAires } from '../reportes/reportes.models';
import { DatosFiscales, DatosFiscalesService } from './datos-fiscales.service';

/** Los campos que se validan en la pantalla, con la misma clave que usa el servidor en sus errores. */
type CampoFiscal = 'razonSocial' | 'domicilioComercial' | 'inicioActividades' | 'ingresosBrutos';

/** Un inicio de actividades posterior a hoy (Buenos Aires): el servidor lo rechaza igual. */
function noFutura(control: AbstractControl<string>): ValidationErrors | null {
  return control.value && control.value > hoyEnBuenosAires() ? { futura: true } : null;
}

const textoONulo = (texto: string) => (texto.trim() === '' ? null : texto.trim());

/**
 * Configuración › Datos de la ferretería (solo el Dueño, CU-01 paso 1): los datos del emisor que lleva cada factura. El
 * super admin carga razón social, CUIT y condición de IVA en el alta; después los edita solo el Dueño, y el CUIT no cambia.
 */
@Component({
  selector: 'app-datos-fiscales',
  imports: [ReactiveFormsModule, BarraAcciones],
  templateUrl: './datos-fiscales.html',
  styleUrl: './datos-fiscales.css',
})
export class PaginaDatosFiscales implements OnInit {
  private readonly service = inject(DatosFiscalesService);
  protected readonly auth = inject(AuthService);

  protected readonly condiciones = CONDICIONES_IVA_FERRETERIA;
  protected readonly etiquetaIva = CONDICION_IVA_FERRETERIA_LABELS;
  protected readonly hoy = hoyEnBuenosAires();

  protected readonly form = inject(FormBuilder).nonNullable.group({
    razonSocial: ['', [Validators.required, Validators.maxLength(150), Validators.pattern(/\S/)]],
    condicionIva: ['RESPONSABLE_INSCRIPTO' as CondicionIvaFerreteria],
    domicilioComercial: ['', Validators.maxLength(200)],
    inicioActividades: ['', noFutura],
    ingresosBrutos: ['', Validators.maxLength(50)],
  });

  protected readonly cuit = signal<string | null>(null);
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
      next: (datos) => {
        this.mostrar(datos);
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
    const valores = this.form.getRawValue();
    this.guardando.set(true);
    this.service
      .guardar({
        razonSocial: valores.razonSocial.trim(),
        condicionIva: valores.condicionIva,
        domicilioComercial: textoONulo(valores.domicilioComercial),
        inicioActividades: valores.inicioActividades === '' ? null : valores.inicioActividades,
        ingresosBrutos: textoONulo(valores.ingresosBrutos),
      })
      .subscribe({
        next: (guardados) => {
          this.guardando.set(false);
          this.mostrar(guardados);
          this.aviso.set('Se guardaron los datos de la ferretería.');
          // El menú muestra la razón social de la sesión: se pide de nuevo para que cambie sin volver a entrar.
          this.auth.refrescarSesion().subscribe({ error: () => undefined });
        },
        error: (err: unknown) => {
          this.guardando.set(false);
          const campos = erroresDeCampos(err);
          if (campos.length > 0) this.erroresGuardado.set(campos);
          else this.errorGuardado.set(mensajeDe(err, 'No se pudieron guardar los datos de la ferretería.'));
        },
      });
  }

  /** El mensaje del campo, una vez tocado; null si está bien. */
  protected mensaje(campo: CampoFiscal): string | null {
    const control = this.form.controls[campo];
    if (!control.touched || control.valid) return null;
    switch (campo) {
      case 'razonSocial':
        return control.hasError('maxlength')
          ? 'La razón social admite hasta 150 caracteres.'
          : 'La razón social es obligatoria.';
      case 'domicilioComercial':
        return 'El domicilio admite hasta 200 caracteres.';
      case 'inicioActividades':
        return 'El inicio de actividades no puede ser una fecha futura.';
      case 'ingresosBrutos':
        return 'Ingresos Brutos admite hasta 50 caracteres.';
    }
  }

  private mostrar(datos: DatosFiscales): void {
    this.cuit.set(datos.cuit);
    this.form.reset({
      razonSocial: datos.razonSocial,
      condicionIva: datos.condicionIva,
      domicilioComercial: datos.domicilioComercial ?? '',
      inicioActividades: datos.inicioActividades ?? '',
      ingresosBrutos: datos.ingresosBrutos ?? '',
    });
  }
}
