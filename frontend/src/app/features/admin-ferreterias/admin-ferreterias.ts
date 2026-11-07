import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { normalizarCuit, validarCuit } from '../../core/formato/cuit';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { CONTRASENA_SOLO_ESPACIOS, ContrasenaForm, NuevaContrasena, noSoloEspacios } from '../../shared/contrasena-form';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { AdminFerreteriasService } from './admin-ferreterias.service';
import {
  AMBIENTE_ARCA_LABELS,
  CONDICIONES_IVA_FERRETERIA,
  CONDICION_IVA_FERRETERIA_LABELS,
  ChequeoFeDummy,
  CondicionIvaFerreteria,
  ESTADO_FERRETERIA_LABELS,
  FeDummyAmbiente,
  FerreteriaAdmin,
} from './ferreteria-admin.models';

/** Los campos del formulario, con la misma clave que usa el servidor en sus errores (`dueno.email`, etc.). */
export type CampoFerreteria = 'razonSocial' | 'cuit' | 'dueno.nombre' | 'dueno.email' | 'dueno.password';

const CAMPOS: readonly CampoFerreteria[] = ['razonSocial', 'cuit', 'dueno.nombre', 'dueno.email', 'dueno.password'];

/**
 * Lo que la barra cambia sobre la fila elegida, siempre con confirmación: suspender o reactivar, y la configuración de
 * ARCA (marca «de prueba», simulador y facturación habilitada). Cada una invierte lo que la fila tiene hoy.
 */
export type AccionFerreteria = 'activo' | 'dePrueba' | 'simulador' | 'facturacion';

interface PedidoDeCambio {
  accion: AccionFerreteria;
  ferreteria: FerreteriaAdmin;
}

interface TextoConfirmacion {
  titulo: string;
  mensaje: string;
  confirmar: string;
  peligro: boolean;
}

const ERROR_POR_ACCION: Record<AccionFerreteria, string> = {
  activo: 'No se pudo cambiar el estado de la ferretería.',
  dePrueba: 'No se pudo cambiar la configuración de ARCA.',
  simulador: 'No se pudo cambiar la configuración de ARCA.',
  facturacion: 'No se pudo cambiar la configuración de ARCA.',
};

const porRazonSocial = (a: FerreteriaAdmin, b: FerreteriaAdmin) =>
  a.razonSocial.localeCompare(b.razonSocial, 'es') || a.id - b.id;

/** «2026-10-21T10:00:00» → «21/10/2026». El servidor manda la hora de Buenos Aires sin zona: no se convierte. */
export function fechaDeAlta(creadoEn: string): string {
  return creadoEn.slice(0, 10).split('-').reverse().join('/');
}

/** Una fila del chequeo FEDummy: el nombre del ambiente y lo que contestó ARCA desde el servidor. */
interface FilaChequeo {
  ambiente: string;
  resultado: FeDummyAmbiente;
}

/**
 * Ferreterías de la plataforma (solo el super admin): estado general, alta con el primer Dueño, suspender o reactivar con
 * confirmación, reset de la contraseña del Dueño y la configuración de ARCA (marca «de prueba», simulador y facturación
 * habilitada, CU-01 paso 2): las reglas las decide el servidor y su 422 se muestra tal cual. Después del alta no edita los
 * datos de la ferretería: los edita el Dueño (CU-01). Además, «Probar ARCA desde el servidor» corre FEDummy en homologación
 * y producción desde el contenedor desplegado. No muestra ningún dato de ventas. Contenedor: habla con el servicio.
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
  protected readonly etiquetaAmbiente = AMBIENTE_ARCA_LABELS;
  protected readonly fechaDeAlta = fechaDeAlta;

  /** El email en dos partes, para que el renglón se parta después de la arroba: «dueno@» / «tornillo.demo». */
  protected partesEmail(email: string): { usuario: string; dominio: string } {
    const arroba = email.indexOf('@');
    return arroba < 0 ? { usuario: email, dominio: '' } : { usuario: email.slice(0, arroba + 1), dominio: email.slice(arroba + 1) };
  }

  protected readonly ferreterias = signal<FerreteriaAdmin[]>([]);
  protected readonly activas = computed(() => this.ferreterias().filter((f) => f.estado === 'ACTIVA').length);
  protected readonly suspendidas = computed(() => this.ferreterias().length - this.activas());
  /** Fila elegida en la grilla: la barra actúa sobre ella. Enter no hace nada: el super admin no edita la ferretería. */
  protected readonly filas = new FilasElegibles(this.ferreterias, () => true, () => undefined);
  protected readonly cargando = signal(true);
  protected readonly cargaFallida = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Error de la pantalla (carga, suspender, configuración de ARCA). */
  protected readonly error = signal<string | null>(null);
  /** Lo que el servidor rechazó al guardar: un mensaje por campo (400 y 409) o uno general. */
  protected readonly erroresServidor = signal<Record<string, string>>({});
  protected readonly errorForm = signal<string | null>(null);
  protected readonly guardando = signal(false);
  /** Se intentó crear con el formulario inválido: desde ahí cada campo muestra su error aunque no se haya escrito. */
  private readonly intentoCrear = signal(false);
  protected readonly cambiando = signal(false);
  /** El cambio que espera confirmación, sobre la ferretería que estaba elegida al pedirlo. */
  protected readonly aConfirmar = signal<PedidoDeCambio | null>(null);
  protected readonly confirmacion = computed(() => {
    const pedido = this.aConfirmar();
    return pedido ? textoConfirmacion(pedido) : null;
  });
  /** La ferretería cuyo Dueño tiene el reset abierto. */
  protected readonly reseteando = signal<FerreteriaAdmin | null>(null);
  protected readonly errorReset = signal<string | null>(null);
  protected readonly enviandoReset = signal(false);
  /** «Probar ARCA desde el servidor» en curso: el botón dice «Probando…». */
  protected readonly chequeando = signal(false);
  /** Lo que contestó el último chequeo FEDummy; null antes de probar o si el pedido falló. */
  protected readonly chequeo = signal<ChequeoFeDummy | null>(null);
  /** Por qué no se pudo hacer el chequeo (el pedido HTTP falló), junto al resultado. */
  protected readonly errorChequeo = signal<string | null>(null);
  protected readonly filasChequeo = computed<FilaChequeo[]>(() => {
    const chequeo = this.chequeo();
    if (!chequeo) return [];
    return [
      { ambiente: AMBIENTE_ARCA_LABELS.HOMOLOGACION, resultado: chequeo.homologacion },
      { ambiente: AMBIENTE_ARCA_LABELS.PRODUCCION, resultado: chequeo.produccion },
    ];
  });

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

  /**
   * El mensaje del campo: primero lo que dijo el servidor, y si no, lo que falla en el formulario una vez que saliste
   * de un campo en el que escribiste, o después de intentar crear. Salir de un campo vacío sin escribir no avisa: el
   * foco vuelve a la razón social después de cada alta y avisaba «obligatoria» con solo tocar otra cosa. QA 05/11.
   */
  protected mensaje(campo: CampoFerreteria): string | null {
    const delServidor = this.erroresServidor()[campo];
    if (delServidor) return delServidor;
    const control = this.form.get(campo);
    if (!control || !control.touched || control.valid) return null;
    if (!control.dirty && !this.intentoCrear()) return null;
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

  /**
   * Sacar el foco de un campo en el que no escribiste no lo da por tocado, mientras no hayas intentado crear. Si no,
   * `ng-touched` + `ng-invalid` lo pintan de rojo en todos lados (`styles.css`) aunque `mensaje()` se calle: pasaba con
   * la razón social que recibe el foco después de cada alta, apenas abrías un diálogo o hacías clic afuera. QA 06/11.
   */
  protected alSalirDeUnCampo(): void {
    if (this.intentoCrear()) return;
    for (const campo of CAMPOS) {
      const control = this.form.get(campo);
      if (control && !control.dirty) control.markAsUntouched();
    }
  }

  /**
   * El segundo clic de un doble clic en «Crear ferretería» no envía: el primero ya creó la ferretería y vació el
   * formulario, y el segundo lo mandaba vacío (errores de obligatorio) o repetido (CUIT ya usado). QA 05/11.
   */
  protected sinDobleClic(evento: MouseEvent): void {
    if (evento.detail > 1) evento.preventDefault();
  }

  protected guardar(): void {
    // Un envío por vez: el botón deshabilitado y el formulario inerte llegan recién en el próximo render.
    if (this.guardando()) return;
    if (this.form.invalid) {
      this.intentoCrear.set(true);
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
        // El formulario estuvo inerte mientras guardaba: queda listo para la próxima alta.
        this.enfocar('#razonSocial');
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

  protected pedirConfirmacion(accion: AccionFerreteria): void {
    const ferreteria = this.filas.elegido();
    if (!ferreteria || this.cambiando()) return;
    this.aviso.set(null);
    this.error.set(null);
    this.aConfirmar.set({ accion, ferreteria });
  }

  /** Aplica el cambio confirmado; la fila queda como la devuelve el servidor. Un 422 muestra su motivo. */
  protected confirmarCambio(): void {
    const pedido = this.aConfirmar();
    if (!pedido) return;
    this.aConfirmar.set(null);
    this.cambiando.set(true);
    this.cambio(pedido).subscribe({
      next: (cambiada) => {
        this.ferreterias.update((lista) => lista.map((f) => (f.id === cambiada.id ? cambiada : f)));
        this.cambiando.set(false);
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, ERROR_POR_ACCION[pedido.accion]));
      },
    });
  }

  /** Cada acción invierte lo que la fila tiene hoy. */
  private cambio({ accion, ferreteria: f }: PedidoDeCambio): Observable<FerreteriaAdmin> {
    switch (accion) {
      case 'activo':
        return this.service.cambiarActivo(f.id, f.estado === 'SUSPENDIDA');
      case 'dePrueba':
        return this.service.cambiarDePrueba(f.id, !f.dePrueba);
      case 'simulador':
        return this.service.cambiarSimulador(f.id, f.ambiente !== 'SIMULADOR');
      case 'facturacion':
        return this.service.cambiarFacturacion(f.id, !f.facturacionHabilitada);
    }
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

  /** FEDummy desde el servidor (S9): prueba la red y el TLS de Railway contra ARCA, sin certificado. */
  protected probarArca(): void {
    if (this.chequeando()) return;
    this.chequeo.set(null);
    this.errorChequeo.set(null);
    this.chequeando.set(true);
    this.service.fedummy().subscribe({
      next: (chequeo) => {
        this.chequeando.set(false);
        this.chequeo.set(chequeo);
      },
      error: (err: unknown) => {
        this.chequeando.set(false);
        this.errorChequeo.set(mensajeDe(err, 'No se pudo probar ARCA desde el servidor.'));
      },
    });
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
    this.intentoCrear.set(false);
    this.form.reset(
      { razonSocial: '', cuit: '', condicionIva: 'RESPONSABLE_INSCRIPTO', dueno: { nombre: '', email: '', password: '' } },
      { emitEvent: false },
    );
  }
}

/** Título, mensaje y botón de la confirmación de cada acción, según lo que la fila tiene hoy. */
function textoConfirmacion({ accion, ferreteria: f }: PedidoDeCambio): TextoConfirmacion {
  const nombre = f.razonSocial;
  switch (accion) {
    case 'activo':
      return f.estado === 'ACTIVA'
        ? {
            titulo: `¿Suspender a ${nombre}?`,
            mensaje: 'Sus usuarios dejan de poder entrar en el acto. No se borra ningún dato y la podés reactivar cuando quieras.',
            confirmar: 'Suspender',
            peligro: true,
          }
        : {
            titulo: `¿Reactivar a ${nombre}?`,
            mensaje: 'Sus usuarios vuelven a poder entrar y todos sus datos están como los dejaron.',
            confirmar: 'Reactivar',
            peligro: false,
          };
    case 'dePrueba':
      return f.dePrueba
        ? {
            titulo: `¿Quitarle la marca de prueba a ${nombre}?`,
            mensaje: 'Ya no va a poder usar el simulador de ARCA.',
            confirmar: 'Quitar marca',
            peligro: false,
          }
        : {
            titulo: `¿Marcar a ${nombre} como de prueba?`,
            mensaje: 'Una ferretería de prueba puede usar el simulador de ARCA, que no tiene valor fiscal.',
            confirmar: 'Marcar de prueba',
            peligro: false,
          };
    case 'simulador':
      return f.ambiente === 'SIMULADOR'
        ? {
            titulo: `¿Quitarle el simulador a ${nombre}?`,
            mensaje: 'Queda sin ambiente de ARCA: no va a poder facturar hasta tener otro.',
            confirmar: 'Quitar simulador',
            peligro: true,
          }
        : {
            titulo: `¿Asignarle el simulador a ${nombre}?`,
            mensaje: 'Sus facturas se autorizan contra el simulador de ARCA, sin valor fiscal. El punto de venta queda en 1.',
            confirmar: 'Asignar simulador',
            peligro: false,
          };
    case 'facturacion':
      return f.facturacionHabilitada
        ? {
            titulo: `¿Deshabilitar la facturación de ${nombre}?`,
            mensaje: 'Deja de emitir facturas electrónicas. Lo que ya se autorizó no cambia.',
            confirmar: 'Deshabilitar',
            peligro: true,
          }
        : {
            titulo: `¿Habilitar la facturación de ${nombre}?`,
            mensaje: 'Desde ahora emite facturas electrónicas en su ambiente de ARCA.',
            confirmar: 'Habilitar',
            peligro: false,
          };
  }
}

