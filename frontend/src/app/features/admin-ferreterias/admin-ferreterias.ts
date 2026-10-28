import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { mensajeDe } from '../../core/api/api-error';
import { normalizarCuit, validarCuit } from '../../core/formato/cuit';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { CONTRASENA_SOLO_ESPACIOS, ContrasenaForm, NuevaContrasena, noSoloEspacios } from '../../shared/contrasena-form';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { AdminFerreteriasService } from './admin-ferreterias.service';
import {
  CONDICIONES_IVA_FERRETERIA,
  CONDICION_IVA_FERRETERIA_LABELS,
  CondicionIvaFerreteria,
  ESTADO_FERRETERIA_LABELS,
  FerreteriaAdmin,
} from './ferreteria-admin.models';

/** Los campos del formulario, con la misma clave que usa el servidor en sus errores (`dueno.email`, etc.). */
export type CampoFerreteria = 'razonSocial' | 'cuit' | 'dueno.nombre' | 'dueno.email' | 'dueno.password';

const porRazonSocial = (a: FerreteriaAdmin, b: FerreteriaAdmin) =>
  a.razonSocial.localeCompare(b.razonSocial, 'es') || a.id - b.id;

/** «2026-10-21T10:00:00» → «21/10/2026». El servidor manda la hora de Buenos Aires sin zona: no se convierte. */
export function fechaDeAlta(creadoEn: string): string {
  return creadoEn.slice(0, 10).split('-').reverse().join('/');
}

/**
 * Ferreterías de la plataforma (solo el super admin): estado general, alta con el primer Dueño, suspender o reactivar con
 * confirmación y reset de la contraseña del Dueño. Después del alta no edita los datos de la ferretería: los edita el Dueño
 * (CU-01). No muestra ningún dato de ventas. Contenedor: habla con el servicio.
 */
@Component({
  selector: 'app-admin-ferreterias',
  imports: [ReactiveFormsModule, BarraAcciones, ContrasenaForm, Confirmacion],
  templateUrl: './admin-ferreterias.html',
  styleUrl: './admin-ferreterias.css',
})
export class AdminFerreterias implements OnInit {
  private readonly service = inject(AdminFerreteriasService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly condiciones = CONDICIONES_IVA_FERRETERIA;
  protected readonly etiquetaIva = CONDICION_IVA_FERRETERIA_LABELS;
  protected readonly etiquetaEstado = ESTADO_FERRETERIA_LABELS;
  protected readonly fechaDeAlta = fechaDeAlta;

  protected readonly ferreterias = signal<FerreteriaAdmin[]>([]);
  protected readonly activas = computed(() => this.ferreterias().filter((f) => f.estado === 'ACTIVA').length);
  protected readonly suspendidas = computed(() => this.ferreterias().length - this.activas());
  /** Fila elegida en la grilla: la barra actúa sobre ella. Enter no hace nada: el super admin no edita la ferretería. */
  protected readonly filas = new FilasElegibles(this.ferreterias, () => true, () => undefined);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Error de la pantalla (carga, suspender). */
  protected readonly error = signal<string | null>(null);
  /** Lo que el servidor rechazó al guardar: un mensaje por campo (400 y 409) o uno general. */
  protected readonly erroresServidor = signal<Record<string, string>>({});
  protected readonly errorForm = signal<string | null>(null);
  protected readonly guardando = signal(false);
  protected readonly cambiando = signal(false);
  /** La ferretería a la que se le pide confirmación para suspenderla o reactivarla. */
  protected readonly aConfirmar = signal<FerreteriaAdmin | null>(null);
  /** La ferretería cuyo Dueño tiene el reset abierto. */
  protected readonly reseteando = signal<FerreteriaAdmin | null>(null);
  protected readonly errorReset = signal<string | null>(null);
  protected readonly enviandoReset = signal(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    razonSocial: ['', [Validators.required, Validators.maxLength(150), Validators.pattern(/\S/)]],
    cuit: ['', [Validators.required, validarCuit]],
    condicionIva: ['RESPONSABLE_INSCRIPTO' as CondicionIvaFerreteria],
    dueno: inject(FormBuilder).nonNullable.group({
      nombre: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
      email: ['', [Validators.required, Validators.email, Validators.maxLength(150)]],
      password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72), noSoloEspacios]],
    }),
  });

  constructor() {
    // Al escribir se van los avisos del servidor: ya no corresponden a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.limpiarErroresForm());
  }

  ngOnInit(): void {
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.error.set(null);
    this.service.listar().subscribe({
      next: (estado) => {
        this.ferreterias.set([...estado.ferreterias].sort(porRazonSocial));
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.ferreterias.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
        this.error.set(mensajeDe(err, 'No se pudieron cargar las ferreterías.'));
      },
    });
  }

  /** El mensaje del campo: primero lo que dijo el servidor, y si no, lo que falla en el formulario una vez tocado. */
  protected mensaje(campo: CampoFerreteria): string | null {
    const delServidor = this.erroresServidor()[campo];
    if (delServidor) return delServidor;
    const control = this.form.get(campo);
    if (!control || !control.touched || control.valid) return null;
    switch (campo) {
      case 'razonSocial':
        return control.hasError('maxlength')
          ? 'La razón social no puede superar 150 caracteres.'
          : 'La razón social es obligatoria.';
      case 'cuit':
        return control.hasError('required') ? 'El CUIT es obligatorio.' : String(control.getError('cuit'));
      case 'dueno.nombre':
        return control.hasError('maxlength') ? 'El nombre no puede superar 100 caracteres.' : 'El nombre es obligatorio.';
      case 'dueno.email':
        return control.hasError('required')
          ? 'El email es obligatorio.'
          : control.hasError('maxlength')
            ? 'El email no puede superar 150 caracteres.'
            : 'El email no es válido.';
      case 'dueno.password':
        return control.hasError('soloEspacios')
          ? CONTRASENA_SOLO_ESPACIOS
          : 'La contraseña tiene que tener entre 8 y 72 caracteres.';
    }
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { razonSocial, cuit, condicionIva, dueno } = this.form.getRawValue();
    this.limpiarErroresForm();
    this.aviso.set(null);
    this.guardando.set(true);
    const alta = {
      razonSocial: razonSocial.trim(),
      cuit: this.cuitNormalizado(cuit),
      condicionIva,
      dueno: { nombre: dueno.nombre.trim(), email: dueno.email.trim(), password: dueno.password },
    };
    this.service.alta(alta).subscribe({
      next: (creada) => {
        this.ferreterias.update((lista) => [...lista, creada].sort(porRazonSocial));
        this.vaciarFormulario();
        this.guardando.set(false);
        this.aviso.set(`Se creó «${creada.razonSocial}».`);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresPorCampo(err);
        if (Object.keys(porCampo).length > 0) this.erroresServidor.set(porCampo);
        else this.errorForm.set(mensajeDe(err, 'No se pudo guardar la ferretería.'));
      },
    });
  }

  /** Nueva: el formulario de alta queda vacío y listo para escribir. */
  protected nueva(): void {
    this.aviso.set(null);
    this.reseteando.set(null);
    this.vaciarFormulario();
    this.limpiarErroresForm();
    this.enfocar('#razonSocial');
  }

  protected pedirConfirmacion(): void {
    const ferreteria = this.filas.elegido();
    if (!ferreteria || this.cambiando()) return;
    this.aviso.set(null);
    this.error.set(null);
    this.aConfirmar.set(ferreteria);
  }

  /** Suspende a la ferretería de la confirmación, o la reactiva si ya estaba suspendida. */
  protected confirmarCambio(): void {
    const ferreteria = this.aConfirmar();
    if (!ferreteria) return;
    this.aConfirmar.set(null);
    this.cambiando.set(true);
    this.service.cambiarActivo(ferreteria.id, ferreteria.estado === 'SUSPENDIDA').subscribe({
      next: (cambiada) => {
        this.ferreterias.update((lista) => lista.map((f) => (f.id === cambiada.id ? cambiada : f)));
        this.cambiando.set(false);
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cambiar el estado de la ferretería.'));
      },
    });
  }

  protected abrirReset(): void {
    const ferreteria = this.filas.elegido();
    if (!ferreteria || ferreteria.duenoId === null) return;
    this.aviso.set(null);
    this.errorReset.set(null);
    this.reseteando.set(ferreteria);
  }

  protected cerrarReset(): void {
    this.reseteando.set(null);
    this.errorReset.set(null);
  }

  protected resetear(nueva: NuevaContrasena): void {
    const ferreteria = this.reseteando();
    if (!ferreteria || ferreteria.duenoId === null) return;
    this.errorReset.set(null);
    this.enviandoReset.set(true);
    this.service.resetearContrasenaDueno(ferreteria.id, ferreteria.duenoId, nueva.nueva).subscribe({
      next: () => {
        this.enviandoReset.set(false);
        this.reseteando.set(null);
        this.aviso.set(`Se cambió la contraseña del Dueño de ${ferreteria.razonSocial}.`);
      },
      error: (err: unknown) => {
        this.enviandoReset.set(false);
        const mensajes = Object.values(erroresPorCampo(err));
        this.errorReset.set(mensajes.length > 0 ? mensajes.join(' ') : mensajeDe(err, 'No se pudo cambiar la contraseña.'));
      },
    });
  }

  protected tituloConfirmacion(f: FerreteriaAdmin): string {
    return f.estado === 'ACTIVA' ? `¿Suspender a ${f.razonSocial}?` : `¿Reactivar a ${f.razonSocial}?`;
  }

  protected mensajeConfirmacion(f: FerreteriaAdmin): string {
    return f.estado === 'ACTIVA'
      ? 'Sus usuarios dejan de poder entrar en el acto. No se borra ningún dato y la podés reactivar cuando quieras.'
      : 'Sus usuarios vuelven a poder entrar y todos sus datos están como los dejaron.';
  }

  private cuitNormalizado(escrito: string): string {
    const resultado = normalizarCuit(escrito);
    return 'cuit' in resultado && resultado.cuit !== null ? resultado.cuit : escrito.trim();
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private limpiarErroresForm(): void {
    this.erroresServidor.set({});
    this.errorForm.set(null);
  }

  private vaciarFormulario(): void {
    this.form.reset(
      { razonSocial: '', cuit: '', condicionIva: 'RESPONSABLE_INSCRIPTO', dueno: { nombre: '', email: '', password: '' } },
      { emitEvent: false },
    );
  }
}

/** Los `errors` de un 400 o 409 (`{ campo: mensaje }`, con claves anidadas como `dueno.email`); vacío si no los trae. */
function erroresPorCampo(err: unknown): Record<string, string> {
  if (!(err instanceof HttpErrorResponse)) return {};
  const errors: unknown = err.error?.errors;
  if (errors === null || typeof errors !== 'object') return {};
  return Object.fromEntries(
    Object.entries(errors).filter(([, m]) => typeof m === 'string' && m.trim() !== ''),
  ) as Record<string, string>;
}
