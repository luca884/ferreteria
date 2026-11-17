import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { erroresDeCampos, erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { CONTRASENA_SOLO_ESPACIOS, ContrasenaForm, NuevaContrasena, noSoloEspacios } from '../../shared/contrasena-form';
import { FilasElegibles } from '../../shared/filas-elegibles';
import { MotivoDeAccion, Motivos, ariaDe } from '../../shared/motivo-de-accion';
import {
  EdicionUsuarioRequest,
  ROLES_DE_FERRETERIA,
  ROL_DE_FERRETERIA_LABELS,
  RolDeFerreteria,
  Usuario,
  leerTope,
  textoTope,
} from './usuario.models';
import { UsuariosService } from './usuarios.service';

const porNombre = (a: Usuario, b: Usuario) => a.nombre.localeCompare(b.nombre) || a.id - b.id;

/** La pantalla del celular (la misma consulta de todo el CSS móvil); sin `matchMedia` (pruebas, impresión) no lo es. */
const enElCelular = (): boolean =>
  typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 639.98px)').matches;

/** S5: lo que se explica al intentar una acción de la barra sin un usuario elegido. */
const MOTIVO_SIN_ELEGIR_EDITAR = 'Elegí un usuario de la lista para editarlo.';
const MOTIVO_SIN_ELEGIR_ALTERNAR = 'Elegí un usuario de la lista para desactivarlo o reactivarlo.';
const MOTIVO_SIN_ELEGIR_RESET = 'Elegí un usuario de la lista para resetearle la contraseña.';
/** La razón de no poder desactivarse a uno mismo: la muestra la línea `#motivo-desactivar`, enlazada al botón. */
const MOTIVO_ES_PROPIO = 'No podés desactivarte ni cambiarte el rol a vos mismo.';

/** Las acciones de la barra que pueden estar apagadas por una regla de negocio (S5). */
type Accion = 'editar' | 'alternar' | 'reset';

const topeValido = (control: AbstractControl<string>): ValidationErrors | null =>
  leerTope(control.value) === undefined ? { tope: true } : null;

/**
 * Usuarios de la ferretería (CU-02, solo el Dueño): alta con contraseña, edición de nombre, email y rol, desactivar o
 * reactivar y resetear la contraseña. No se borran: un desactivado pierde el acceso en el acto. El Dueño no puede
 * desactivarse ni cambiarse el rol a sí mismo (el servidor igual lo controla). Contenedor: habla con el servicio.
 */
@Component({
  selector: 'app-usuarios',
  imports: [ReactiveFormsModule, BarraAcciones, ContrasenaForm, Confirmacion],
  templateUrl: './usuarios.html',
  styleUrl: './usuarios.css',
})
export class Usuarios implements OnInit {
  private readonly service = inject(UsuariosService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  protected readonly roles = ROLES_DE_FERRETERIA;
  protected readonly etiquetaRol = ROL_DE_FERRETERIA_LABELS;
  protected readonly textoTope = textoTope;

  protected readonly usuarios = signal<Usuario[]>([]);
  /** Fila elegida en la grilla: la barra actúa sobre ella; Enter edita. */
  protected readonly filas = new FilasElegibles(this.usuarios, () => true, (u) => this.editar(u));
  protected readonly cargando = signal(true);
  /** La lista no se pudo traer (por ejemplo, 403 a un Vendedor): se avisa y no se muestra ninguna fila. */
  protected readonly cargaFallida = signal(false);
  protected readonly sinPermiso = signal(false);
  protected readonly incluirInactivos = signal(false);
  protected readonly aviso = signal<string | null>(null);
  /** Error de la pantalla (carga, desactivar). */
  protected readonly error = signal<string | null>(null);
  /** Lo que el servidor rechazó al guardar: 409 y 422 en `errorForm`, un mensaje por campo en `erroresCampos` (400). */
  protected readonly errorForm = signal<string | null>(null);
  protected readonly erroresCampos = signal<string[]>([]);
  protected readonly guardando = signal(false);
  protected readonly cambiando = signal(false);
  /**
   * La ficha a pantalla completa del celular (S7 de movil-sectores): `nuevo`, `editar` y tocar una fila la abren; guardar,
   * cancelar, «Volver» y los cambios de estado o de contraseña la cierran. Sólo la lee el CSS del celular: en el
   * escritorio el formulario siempre está al costado.
   */
  protected readonly fichaAbierta = signal(false);
  /** El usuario que se está por desactivar: espera la confirmación. */
  protected readonly aDesactivar = signal<Usuario | null>(null);
  /** El usuario que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editando = signal<Usuario | null>(null);
  /** El usuario al que se le está reseteando la contraseña; null si no hay reset abierto. */
  protected readonly reseteando = signal<Usuario | null>(null);
  /** Error del reseteo que no es de un campo (403, caída de red…). */
  protected readonly errorReset = signal<string | null>(null);
  /** El 400 del reseteo: el DTO llama `password` al campo y va debajo de «Contraseña nueva». */
  protected readonly errorNueva = signal<string | null>(null);
  protected readonly enviandoReset = signal(false);
  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S5). */
  protected readonly motivo = new MotivoDeAccion(() => this.motivos());
  /**
   * Por qué cada acción de la barra no se puede usar; null en la que sí se puede. Sobre uno mismo la razón ya está a la
   * vista en `#motivo-desactivar` (enlazada con aria-describedby) y no se repite en el motivo de la acción.
   */
  protected readonly motivos = computed<Motivos<Accion>>(() => {
    const usuario = this.filas.elegido();
    return {
      editar: usuario ? null : MOTIVO_SIN_ELEGIR_EDITAR,
      alternar: usuario ? (this.esPropio(usuario) ? MOTIVO_ES_PROPIO : null) : MOTIVO_SIN_ELEGIR_ALTERNAR,
      reset: usuario ? null : MOTIVO_SIN_ELEGIR_RESET,
    };
  });
  protected readonly motivoEsPropio = MOTIVO_ES_PROPIO;
  /** Para la plantilla: 'true' apaga el botón, null lo deja prendido. */
  protected readonly ariaDe = ariaDe;

  protected readonly contrasenaSoloEspacios = CONTRASENA_SOLO_ESPACIOS;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(150)]],
    rol: ['VENDEDOR' as RolDeFerreteria],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72), noSoloEspacios]],
    /** CU-07: solo para un Vendedor; vacío = sin tope. */
    topeDescuento: ['', topeValido],
    telefono: ['', [Validators.maxLength(30)]],
  });

  constructor() {
    // Al escribir se va el aviso de error del servidor: ya no corresponde a lo que hay en el formulario.
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.limpiarErroresForm());
  }

  ngOnInit(): void {
    this.cargar();
  }

  /** El usuario logueado: sobre sí mismo no se desactiva ni se cambia el rol. */
  protected esPropio(usuario: Usuario | null): boolean {
    return usuario !== null && usuario.id === this.auth.sesion()?.userId;
  }

  /** Carga la lista; también la usan Reintentar y el filtro «Mostrar desactivados». */
  protected cargar(): void {
    this.cargando.set(true);
    this.cargaFallida.set(false);
    this.sinPermiso.set(false);
    this.error.set(null);
    this.service.listar(this.incluirInactivos()).subscribe({
      next: (lista) => {
        this.usuarios.set([...lista].sort(porNombre));
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.usuarios.set([]);
        this.cargando.set(false);
        this.cargaFallida.set(true);
        const sinPermiso = err instanceof HttpErrorResponse && err.status === 403;
        this.sinPermiso.set(sinPermiso);
        // Como las demás pantallas del Dueño: decir qué es lo que solo puede él, no el «sin permiso» genérico.
        this.error.set(
          sinPermiso
            ? 'Solo el Dueño puede administrar los usuarios.'
            : mensajeDe(err, 'No se pudieron cargar los usuarios.'),
        );
      },
    });
  }

  protected alternarInactivos(incluir: boolean): void {
    this.incluirInactivos.set(incluir);
    this.cargar();
  }

  /** El campo del tope solo va para un Vendedor: el Dueño no tiene tope. */
  protected conTope(): boolean {
    return this.form.controls.rol.value === 'VENDEDOR';
  }

  protected guardar(): void {
    if (!this.conTope()) this.form.controls.topeDescuento.setValue('', { emitEvent: false });
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.form.markAllAsDirty();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, email, rol, password, topeDescuento, telefono } = this.form.getRawValue();
    const usuario = this.editando();
    const datos: EdicionUsuarioRequest = {
      nombre: nombre.trim(),
      email: email.trim(),
      rol,
      topeDescuento: rol === 'VENDEDOR' ? (leerTope(topeDescuento) ?? null) : null,
      telefono: telefono.trim() === '' ? null : telefono.trim(),
    };
    this.limpiarErroresForm();
    this.aviso.set(null);
    this.guardando.set(true);
    const llamada =
      usuario === null
        ? this.service.crear({ ...datos, password })
        : this.service.actualizar(usuario.id, datos);
    llamada.subscribe({
      next: (guardado) => {
        this.usuarios.update((lista) => [...lista.filter((u) => u.id !== guardado.id), guardado].sort(porNombre));
        this.vaciarFormulario();
        this.guardando.set(false);
        // El formulario estuvo inerte mientras guardaba: queda listo para el próximo alta, como con Nuevo.
        this.enfocar('#nombre');
        this.cerrarFicha();
        this.aviso.set(`Se guardó «${guardado.nombre}».`);
        // D9: si el Dueño se editó a sí mismo, el shell tiene que mostrar el nombre nuevo sin esperar al próximo login.
        if (this.esPropio(guardado)) this.auth.refrescarSesion().subscribe({ error: () => undefined });
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        const porCampo = erroresDeCampos(err);
        if (porCampo.length > 0) this.erroresCampos.set(porCampo);
        else this.errorForm.set(mensajeDe(err, 'No se pudo guardar el usuario.'));
      },
    });
  }

  /** Editar (barra, Enter o doble clic): precarga al usuario en el formulario de al lado. */
  protected editar(usuario: Usuario | null = this.filas.elegido()): void {
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación. Enter y doble clic
    // traen su propio usuario, así que la acción mira el argumento y no la tabla.
    if (!usuario) {
      this.motivo.mostrar('editar');
      return;
    }
    this.aviso.set(null);
    this.reseteando.set(null);
    this.filas.elegidoId.set(usuario.id);
    this.editando.set(usuario);
    this.fichaAbierta.set(true);
    this.form.controls.password.disable({ emitEvent: false });
    this.ajustarRol(usuario);
    this.form.reset({
      nombre: usuario.nombre,
      email: usuario.email,
      rol: usuario.rol,
      password: '',
      topeDescuento: usuario.topeDescuento == null ? '' : String(usuario.topeDescuento).replace('.', ','),
      telefono: usuario.telefono ?? '',
    });
    this.limpiarErroresForm();
  }

  protected cancelar(): void {
    this.vaciarFormulario();
    this.limpiarErroresForm();
  }

  /** En el celular, tocar un usuario de la lista abre su ficha; en el escritorio sólo lo elige (Enter o doble clic editan). */
  protected abrirFichaEnElCelular(usuario: Usuario): void {
    if (!enElCelular()) return;
    this.editar(usuario);
    this.enfocar('form.alta .ficha-volver');
  }

  /** «Volver» y «Cancelar» de la ficha del celular: se descarta lo escrito y el foco vuelve al botón «Nuevo». */
  protected volverDeLaFicha(): void {
    this.cancelar();
    this.cerrarFicha();
  }

  /** Cierra la ficha y, en el celular, devuelve el foco a «Nuevo»; en el escritorio el foco se queda donde estaba. */
  private cerrarFicha(): void {
    this.fichaAbierta.set(false);
    if (enElCelular()) this.enfocar('.boton-flotante');
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.aviso.set(null);
    this.reseteando.set(null);
    this.cancelar();
    this.fichaAbierta.set(true);
    this.enfocar('#nombre');
  }

  /** Desactivar pide confirmación (un clic deja al empleado sin acceso); reactivar es directo. */
  protected alternarActivo(): void {
    const usuario = this.filas.elegido();
    // S5: sin fila elegida el botón avisa el motivo; mientras cambia sigue apagado por el disabled transitorio.
    if (!usuario) {
      this.motivo.mostrar('alternar');
      return;
    }
    if (this.cambiando()) return;
    // Sobre uno mismo la razón ya está a la vista: se explica sola y no se abre la confirmación.
    if (this.esPropio(usuario)) return;
    if (usuario.activo) this.aDesactivar.set(usuario);
    else this.cambiarActivo(usuario);
  }

  protected confirmarDesactivar(): void {
    const usuario = this.aDesactivar();
    this.aDesactivar.set(null);
    if (usuario) this.cambiarActivo(usuario);
  }

  /** Sobre uno mismo el botón no se ofrece; el servidor igual dice 422. */
  private cambiarActivo(usuario: Usuario): void {
    if (this.cambiando()) return;
    this.aviso.set(null);
    this.error.set(null);
    this.cambiando.set(true);
    this.service.cambiarActivo(usuario.id, !usuario.activo).subscribe({
      next: (cambiado) => {
        this.usuarios.update((lista) => lista.map((u) => (u.id === cambiado.id ? cambiado : u)));
        this.cambiando.set(false);
        // Desde la ficha del celular, desactivar o reactivar termina la tarea: se vuelve a la lista.
        this.cerrarFicha();
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cambiar el estado del usuario.'));
      },
    });
  }

  protected abrirReset(): void {
    // S5: sin fila elegida el botón avisa el motivo en vez de quedar apagado sin explicación.
    const usuario = this.filas.elegido();
    if (this.motivo.mostrar('reset') || usuario === null) return;
    this.aviso.set(null);
    this.limpiarErroresDeReset();
    this.reseteando.set(usuario);
    if (enElCelular()) this.enfocar('section.alta .ficha-volver');
  }

  protected cerrarReset(): void {
    this.reseteando.set(null);
    this.limpiarErroresDeReset();
  }

  /**
   * Los dos errores del reset (el general y el del campo) se limpian juntos: al abrir, al cerrar, al reenviar y cuando
   * el usuario tipea, porque en los cuatro casos ya no describen lo que hay en pantalla.
   */
  protected limpiarErroresDeReset(): void {
    this.errorReset.set(null);
    this.errorNueva.set(null);
  }

  protected resetear(nueva: NuevaContrasena): void {
    const usuario = this.reseteando();
    if (!usuario) return;
    this.limpiarErroresDeReset();
    this.enviandoReset.set(true);
    this.service.resetearContrasena(usuario.id, nueva.nueva).subscribe({
      next: () => {
        this.enviandoReset.set(false);
        this.reseteando.set(null);
        this.cerrarFicha();
        this.aviso.set(`Se cambió la contraseña de ${usuario.nombre}.`);
      },
      error: (err: unknown) => {
        this.enviandoReset.set(false);
        // El endpoint de reseteo llama `password` al campo: su 400 va debajo de «Contraseña nueva».
        const nuevaRechazada = erroresPorCampo(err)['password'];
        if (nuevaRechazada !== undefined) {
          this.errorNueva.set(nuevaRechazada);
          return;
        }
        const porCampo = erroresDeCampos(err);
        this.errorReset.set(
          porCampo.length > 0 ? porCampo.join(' ') : mensajeDe(err, 'No se pudo cambiar la contraseña.'),
        );
      },
    });
  }

  protected invalido(campo: 'nombre' | 'email' | 'password' | 'topeDescuento' | 'telefono'): boolean {
    const control = this.form.controls[campo];
    // Sin escribir nada, avisa recién al intentar guardar, que lo marca modificado (bugs de la guía, S14).
    return control.touched && control.dirty && control.invalid;
  }

  /** El rol elegido con el control segmentado del celular: escribe el mismo control del formulario que el select. */
  protected elegirRol(rol: RolDeFerreteria): void {
    const control = this.form.controls.rol;
    if (control.disabled) return;
    control.setValue(rol);
    control.markAsDirty();
  }

  /** Como un grupo de radios nativo: las flechas eligen el rol anterior o el siguiente (dando la vuelta) y llevan el foco. */
  protected alTeclearRol(evento: KeyboardEvent, indice: number): void {
    const paso = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[evento.key];
    if (paso === undefined || this.form.controls.rol.disabled) return;
    evento.preventDefault();
    const destino = (indice + paso + this.roles.length) % this.roles.length;
    this.elegirRol(this.roles[destino]);
    (evento.currentTarget as HTMLElement).parentElement?.querySelectorAll('button')[destino]?.focus();
  }

  /** Sobre uno mismo el rol no se cambia: el selector (y el control segmentado del celular) queda deshabilitado. */
  private ajustarRol(usuario: Usuario | null): void {
    const rol = this.form.controls.rol;
    if (this.esPropio(usuario)) rol.disable({ emitEvent: false });
    else rol.enable({ emitEvent: false });
  }

  /** Después de pintar, enfoca el primer elemento que coincida. */
  private enfocar(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private limpiarErroresForm(): void {
    this.errorForm.set(null);
    this.erroresCampos.set([]);
  }

  private vaciarFormulario(): void {
    this.editando.set(null);
    this.form.controls.password.enable({ emitEvent: false });
    this.ajustarRol(null);
    this.form.reset({ nombre: '', email: '', rol: 'VENDEDOR', password: '', topeDescuento: '', telefono: '' });
  }
}
