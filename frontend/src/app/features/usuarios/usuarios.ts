import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { ContrasenaForm, NuevaContrasena } from '../../shared/contrasena-form';
import { FilasElegibles } from '../../shared/filas-elegibles';
import {
  EdicionUsuarioRequest,
  ROLES_DE_FERRETERIA,
  ROL_DE_FERRETERIA_LABELS,
  RolDeFerreteria,
  Usuario,
} from './usuario.models';
import { UsuariosService } from './usuarios.service';

const porNombre = (a: Usuario, b: Usuario) => a.nombre.localeCompare(b.nombre) || a.id - b.id;

/**
 * Usuarios de la ferretería (CU-02, solo el Dueño): alta con contraseña, edición de nombre, email y rol, desactivar o
 * reactivar y resetear la contraseña. No se borran: un desactivado pierde el acceso en el acto. El Dueño no puede
 * desactivarse ni cambiarse el rol a sí mismo (el servidor igual lo controla). Contenedor: habla con el servicio.
 */
@Component({
  selector: 'app-usuarios',
  imports: [ReactiveFormsModule, BarraAcciones, ContrasenaForm],
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
  /** El usuario que se está editando; null mientras se da de alta uno nuevo. */
  protected readonly editando = signal<Usuario | null>(null);
  /** El usuario al que se le está reseteando la contraseña; null si no hay reset abierto. */
  protected readonly reseteando = signal<Usuario | null>(null);
  protected readonly errorReset = signal<string | null>(null);
  protected readonly enviandoReset = signal(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(150)]],
    rol: ['VENDEDOR' as RolDeFerreteria],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
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
        this.sinPermiso.set(err instanceof HttpErrorResponse && err.status === 403);
        this.error.set(mensajeDe(err, 'No se pudieron cargar los usuarios.'));
      },
    });
  }

  protected alternarInactivos(incluir: boolean): void {
    this.incluirInactivos.set(incluir);
    this.cargar();
  }

  protected guardar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.enfocar('form.alta [aria-invalid="true"]');
      return;
    }
    const { nombre, email, rol, password } = this.form.getRawValue();
    const usuario = this.editando();
    const datos: EdicionUsuarioRequest = { nombre: nombre.trim(), email: email.trim(), rol };
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
    if (!usuario) return;
    this.aviso.set(null);
    this.reseteando.set(null);
    this.filas.elegidoId.set(usuario.id);
    this.editando.set(usuario);
    this.form.controls.password.disable({ emitEvent: false });
    this.ajustarRol(usuario);
    this.form.reset({ nombre: usuario.nombre, email: usuario.email, rol: usuario.rol, password: '' });
    this.limpiarErroresForm();
  }

  protected cancelar(): void {
    this.vaciarFormulario();
    this.limpiarErroresForm();
  }

  /** Nuevo: el formulario vuelve al alta y queda listo para escribir. */
  protected nuevo(): void {
    this.aviso.set(null);
    this.reseteando.set(null);
    this.cancelar();
    this.enfocar('#nombre');
  }

  /** Desactiva al elegido, o lo reactiva si ya está desactivado. Sobre uno mismo el botón no se ofrece; el servidor igual dice 422. */
  protected cambiarActivo(): void {
    const usuario = this.filas.elegido();
    if (!usuario || this.cambiando()) return;
    this.aviso.set(null);
    this.error.set(null);
    this.cambiando.set(true);
    this.service.cambiarActivo(usuario.id, !usuario.activo).subscribe({
      next: (cambiado) => {
        this.usuarios.update((lista) => lista.map((u) => (u.id === cambiado.id ? cambiado : u)));
        this.cambiando.set(false);
      },
      error: (err: unknown) => {
        this.cambiando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cambiar el estado del usuario.'));
      },
    });
  }

  protected abrirReset(): void {
    const usuario = this.filas.elegido();
    if (!usuario) return;
    this.aviso.set(null);
    this.errorReset.set(null);
    this.reseteando.set(usuario);
  }

  protected cerrarReset(): void {
    this.reseteando.set(null);
    this.errorReset.set(null);
  }

  protected resetear(nueva: NuevaContrasena): void {
    const usuario = this.reseteando();
    if (!usuario) return;
    this.errorReset.set(null);
    this.enviandoReset.set(true);
    this.service.resetearContrasena(usuario.id, nueva.nueva).subscribe({
      next: () => {
        this.enviandoReset.set(false);
        this.reseteando.set(null);
        this.aviso.set(`Se cambió la contraseña de ${usuario.nombre}.`);
      },
      error: (err: unknown) => {
        this.enviandoReset.set(false);
        const porCampo = erroresDeCampos(err);
        this.errorReset.set(
          porCampo.length > 0 ? porCampo.join(' ') : mensajeDe(err, 'No se pudo cambiar la contraseña.'),
        );
      },
    });
  }

  protected invalido(campo: 'nombre' | 'email' | 'password'): boolean {
    const control = this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** Sobre uno mismo el rol no se cambia: el selector queda deshabilitado. */
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
    this.form.reset({ nombre: '', email: '', rol: 'VENDEDOR', password: '' });
  }
}
