import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { mensajeDe } from '../../core/api/api-error';
import { AltaTotp, SegundoPaso } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { esRutaPropia } from '../../core/auth/ruta-propia';
import { barras } from '../etiquetas/codigo-barras';

/** El `message` genérico del 401 del backend (CredencialesInvalidasException). */
const GENERICO_DEL_SERVIDOR = 'Email o contraseña incorrectos';

const SIN_CONEXION = 'No pudimos conectar con el servidor. Probá de nuevo.';

const DEMASIADOS_INTENTOS = 'Demasiados intentos. Probá de nuevo en unos minutos.';

/** Los textos del backend para el segundo paso, por si un error llega sin `message` (totp-super-admin, Contrato). */
const TOTP_VENCIDO = 'Pasaron más de 5 minutos. Volvé a ingresar tu email y contraseña.';
const CODIGO_INCORRECTO = 'El código no es correcto. Fijate en la app y probá de nuevo.';

/** Los campos en el orden de la pantalla: al enviar con errores se enfoca el primero que falla. */
const CAMPOS = ['email', 'password'] as const;
type Campo = (typeof CAMPOS)[number] | 'codigo';

/** Email y contraseña y, para el super admin, el segundo paso: activar el código de la app o escribirlo. */
type Paso = 'credenciales' | SegundoPaso;

/** Módulos de silencio alrededor del QR (el margen blanco que pide el estándar), como el de la factura. */
const SILENCIO_QR = 4;
/** El lado del QR en pantalla, más o menos: se redondea a píxeles enteros por módulo para que quede nítido. */
const LADO_QR_PX = 200;

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, NgTemplateOutlet],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  /** El código de la app: 6 números (Google Authenticator lo muestra en dos grupos de 3, «123 456»). */
  protected readonly formCodigo = inject(FormBuilder).nonNullable.group({
    codigo: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly paso = signal<Paso>('credenciales');
  /** La clave y el QR para activar el código de la app; null hasta que llegan (y fuera del alta). */
  protected readonly alta = signal<AltaTotp | null>(null);
  /**
   * El permiso del segundo paso que dio el login. Vive solo acá, en memoria: nunca en el AuthService ni en
   * localStorage, así el super admin no queda «autenticado» sin el código. Volver o vencer lo olvida.
   */
  private tokenTotp: string | null = null;
  private pedidoDeAlta: Subscription | null = null;
  /**
   * El servidor cerró la sesión abierta (ferretería suspendida, usuario dado de baja, rol cambiado o vencida): el
   * interceptor manda acá con `sesion=cerrada` (bugs de la guía, S15). El motivo, si es la suspensión, lo dice el
   * login al volver a ingresar.
   */
  protected readonly sesionCerrada = this.route.snapshot.queryParamMap.get('sesion') === 'cerrada';

  /** Los módulos oscuros del QR del alta como `<rect>`, fila por fila (como el QR de la factura). Null sin QR. */
  protected readonly qr = computed(() => {
    const filas = this.alta()?.qr;
    if (!filas || filas.length === 0) return null;
    const lado = filas.length + 2 * SILENCIO_QR;
    return {
      viewBox: `${-SILENCIO_QR} ${-SILENCIO_QR} ${lado} ${lado}`,
      tamano: Math.max(1, Math.round(LADO_QR_PX / lado)) * lado,
      modulos: filas.flatMap((fila, y) => barras(fila).map((b) => ({ x: b.x, y, ancho: b.ancho }))),
    };
  });

  /** La clave en grupos de 4, para copiarla a mano sin perderse (Google Authenticator ignora los espacios). */
  protected readonly claveEnGrupos = computed(() => this.alta()?.clave.match(/.{1,4}/g)?.join(' ') ?? '');

  constructor() {
    // Solo números y hasta 6: así se puede pegar «123 456» tal como lo muestra la app.
    const codigo = this.formCodigo.controls.codigo;
    codigo.valueChanges.pipe(takeUntilDestroyed()).subscribe((valor) => {
      const limpio = valor.replace(/\D/g, '').slice(0, 6);
      if (limpio !== valor) codigo.setValue(limpio, { emitEvent: false });
    });
  }

  /**
   * La URL que pidió antes de loguearse (returnUrl), solo si es una ruta propia: empieza con una sola «/» y
   * no es el login. Si no, la raíz, que reparte por rol.
   */
  private destino(): string {
    const pedida = this.route.snapshot.queryParamMap.get('returnUrl');
    return esRutaPropia(pedida) ? pedida : '/';
  }

  protected invalido(campo: Campo): boolean {
    const control = campo === 'codigo' ? this.formCodigo.controls.codigo : this.form.controls[campo];
    return control.touched && control.invalid;
  }

  /** El error del campo y, si lo hay, el del servidor (vale para los dos: email o contraseña). */
  protected descripcion(campo: Campo): string | null {
    const ids = [this.invalido(campo) ? `${campo}-error` : null, this.error() ? 'login-error' : null];
    return ids.filter(Boolean).join(' ') || null;
  }

  protected ingresar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      const primero = CAMPOS.find((campo) => this.form.controls[campo].invalid);
      if (primero) this.host.querySelector<HTMLElement>(`#${primero}`)?.focus();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: (res) => {
        // El super admin todavía no entra: falta el código de la app (totp-super-admin, S1).
        if (res.segundoPaso) this.empezarSegundoPaso(res.segundoPaso, res.tokenTotp);
        else void this.router.navigateByUrl(this.destino());
      },
      error: (err: unknown) => {
        this.enviando.set(false);
        this.error.set(this.mensajeDeError(err));
      },
    });
  }

  /** El código de la app: si está bien, entra igual que con el login de siempre. */
  protected verificar(): void {
    const codigo = this.formCodigo.controls.codigo;
    const tokenTotp = this.tokenTotp;
    if (tokenTotp === null) return;
    if (codigo.invalid) {
      codigo.markAsTouched();
      this.host.querySelector<HTMLElement>('#codigo')?.focus();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    this.auth.verificarTotp(tokenTotp, codigo.value).subscribe({
      next: () => void this.router.navigateByUrl(this.destino()),
      error: (err: unknown) => {
        this.enviando.set(false);
        this.fallaDelSegundoPaso(err);
      },
    });
  }

  /** Vuelve a email y contraseña (que quedan escritos) y olvida el `tokenTotp`: el próximo ingreso pide uno nuevo. */
  protected volver(): void {
    this.pedidoDeAlta?.unsubscribe();
    this.pedidoDeAlta = null;
    this.tokenTotp = null;
    this.alta.set(null);
    this.formCodigo.reset();
    this.enviando.set(false);
    this.error.set(null);
    this.paso.set('credenciales');
    this.enfocarDespues('email');
  }

  /** En el alta pide la clave y el QR apenas entra; el código se escribe en los dos pasos, y ahí va el foco. */
  private empezarSegundoPaso(paso: SegundoPaso, tokenTotp: string): void {
    this.tokenTotp = tokenTotp;
    this.alta.set(null);
    this.formCodigo.reset();
    this.enviando.set(false);
    this.paso.set(paso);
    this.enfocarDespues('codigo');
    if (paso === 'ALTA') {
      this.pedidoDeAlta = this.auth.altaTotp(tokenTotp).subscribe({
        next: (alta) => this.alta.set(alta),
        error: (err: unknown) => this.fallaDelSegundoPaso(err),
      });
    }
  }

  /**
   * Código mal: se queda, con el campo vacío. Cualquier otro 401 es el `tokenTotp` vencido (o inválido): no hay más
   * que hacer en este paso, así que vuelve a email y contraseña con el aviso. El resto, el mensaje del servidor.
   */
  private fallaDelSegundoPaso(err: unknown): void {
    const motivo: unknown = err instanceof HttpErrorResponse ? err.error?.errors?.motivo : undefined;
    if (err instanceof HttpErrorResponse && err.status === 401 && motivo !== 'CODIGO_INCORRECTO') {
      this.volver();
      this.error.set(mensajeDe(err, TOTP_VENCIDO));
      return;
    }
    if (motivo === 'CODIGO_INCORRECTO') {
      this.formCodigo.reset();
      this.enfocarDespues('codigo');
    }
    this.error.set(this.mensajeDelSegundoPaso(err));
  }

  private mensajeDelSegundoPaso(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return SIN_CONEXION;
    if (err.status === 401) return mensajeDe(err, CODIGO_INCORRECTO);
    if (err.status === 429) return mensajeDe(err, DEMASIADOS_INTENTOS);
    // 400 (no son 6 números) y 409 (ya activado, o sin el QR pedido): el servidor dice qué pasó.
    if (err.status === 400 || err.status === 409) return mensajeDe(err, SIN_CONEXION);
    return SIN_CONEXION;
  }

  /** Enfoca el campo después de pintar el paso nuevo (antes no existe). */
  private enfocarDespues(id: string): void {
    afterNextRender(() => this.host.querySelector<HTMLElement>(`#${id}`)?.focus(), { injector: this.injector });
  }

  private mensajeDeError(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return SIN_CONEXION;
    if (err.status === 401) return this.mensajeDeCredenciales(err);
    // Límite de intentos: el servidor dice cuántos minutos faltan.
    if (err.status === 429) return mensajeDe(err, DEMASIADOS_INTENTOS);
    return SIN_CONEXION;
  }

  /** El servidor explica un 401 solo cuando hay algo que decir (ferretería suspendida); si no, el texto de siempre. */
  private mensajeDeCredenciales(err: HttpErrorResponse): string {
    const mensaje = mensajeDe(err, GENERICO_DEL_SERVIDOR);
    return mensaje === GENERICO_DEL_SERVIDOR ? 'Email o contraseña incorrectos.' : mensaje;
  }
}
