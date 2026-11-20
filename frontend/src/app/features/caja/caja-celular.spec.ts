import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Caja } from './caja';
import { CajaAbierta, CajaVigente, DetalleCaja } from './caja.models';
import { CajaService } from './caja.service';

registerLocaleData(localeEsAr);

/**
 * La Caja en el celular (movil-sectores T11a, S1, S2, S5, S7): sólo cambia cómo se ve. Los tests de plata corren en
 * escritorio y en celular con los mismos literales (los pedidos al servidor, los textos y los errores son los de hoy);
 * los de estructura miran lo propio del celular: el ⋯ con el historial, el pie fijo, la fila «Cerrar la caja» y la
 * pantalla completa del cierre. En escritorio y en tablet (matchMedia de 1023.98 px) no aparece nada de eso.
 */
describe('Caja en el celular (T11a)', () => {
  const abierta = (cambios: Partial<CajaAbierta> = {}): CajaAbierta => ({
    id: 4,
    estado: 'ABIERTA',
    fechaApertura: '2026-10-16T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 5000,
    ...cambios,
  });
  const sinCaja = (propuesto = 0, hayCierreAnterior = true): CajaVigente => ({
    estado: 'SIN_CAJA',
    caja: null,
    efectivoPropuesto: propuesto,
    hayCierreAnterior,
  });
  const conCaja = (caja = abierta()): CajaVigente => ({
    estado: caja.estado as 'ABIERTA',
    caja,
    efectivoPropuesto: 0,
    hayCierreAnterior: true,
  });
  const detalle = (cambios: Partial<DetalleCaja> = {}): DetalleCaja => ({
    id: 4,
    estado: 'ABIERTA',
    fechaApertura: '2026-10-16T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 5000,
    efectivoCierreAnterior: null,
    diferenciaApertura: null,
    fechaCierre: null,
    cerradaPor: null,
    ventas: { EFECTIVO: 1800, DEBITO: 1000, CREDITO: 800, TRANSFERENCIA: 1000, CUENTA_CORRIENTE: 0 },
    totalVentas: 4600,
    cobranzas: { EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 0,
    ingresos: 200,
    gastos: 300,
    retiros: 1000,
    efectivoEsperado: 5700,
    efectivoContado: null,
    diferencia: null,
    movimientos: [],
    ...cambios,
  });

  type Modo = 'escritorio' | 'tablet' | 'celular';
  let fixture: ComponentFixture<Caja>;
  let servicio: Record<'actual' | 'abrir' | 'registrarMovimiento' | 'cerrar' | 'detalle', ReturnType<typeof vi.fn>>;
  let router: Router;

  async function crear(vigente: CajaVigente, opciones: { dueno?: boolean; modo?: Modo } = {}) {
    const modo = opciones.modo ?? 'celular';
    if (modo === 'celular') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('639.98px'), media: c }));
    } else if (modo === 'tablet') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('1023.98px'), media: c }));
    }
    servicio = {
      actual: vi.fn(() => of(vigente)),
      abrir: vi.fn((efectivo: number) => of(abierta({ efectivoInicial: efectivo }))),
      registrarMovimiento: vi.fn((_id: number, m: { tipo: string; importe: number; motivo: string }) =>
        of({ id: 1, ...m, fecha: '2026-10-16T10:00:00', usuario: 'Ana' }),
      ),
      cerrar: vi.fn((_id: number, contado: number) =>
        of(detalle({ estado: 'CERRADA', efectivoContado: contado, diferencia: contado - 5700, cerradaPor: 'Ana' })),
      ),
      detalle: vi.fn(() => of(detalle())),
    };
    await TestBed.configureTestingModule({
      imports: [Caja],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CajaService, useValue: servicio },
        {
          provide: AuthService,
          useValue: { esDueno: signal(opciones.dueno ?? false), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) },
        },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Caja);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const campo = (id: string) => q<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;

  async function escribir(id: string, valor: string) {
    const c = campo(id);
    c.value = valor;
    c.dispatchEvent(new Event(c instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }
  async function enviar(selector: string) {
    q(selector)!.dispatchEvent(new Event('submit'));
    await refrescar();
  }
  async function tocar(selector: string) {
    q(selector)!.click();
    await refrescar();
  }
  const botonConfirmar = () => q<HTMLButtonElement>('app-confirmacion button.confirmar')!;
  const botonCancelar = () => q<HTMLButtonElement>('app-confirmacion button.cancelar')!;
  /** En el celular el cierre es una pantalla aparte que se abre con la fila «Cerrar la caja»; en escritorio ya está en la página. */
  async function irAlCierre() {
    if (q('.fila-cerrar-caja')) await tocar('.fila-cerrar-caja');
  }
  function tecla(destino: Element, key: string) {
    const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    destino.dispatchEvent(evento);
    return evento;
  }
  const rechazo = (mensaje: string, status = 409) =>
    throwError(() => new HttpErrorResponse({ status, error: { status, message: mensaje } }));

  // ---------------------------------------------------------------- plata: igual en escritorio y en celular
  describe.each<Modo>(['escritorio', 'celular'])('los pedidos y los textos son los de hoy (%s)', (modo) => {
    it('abrir: manda el efectivo inicial editado y avisa «Abriste la caja.»', async () => {
      await crear(sinCaja(7000), { modo });

      await escribir('efectivo-inicial', '6500');
      await enviar('form.apertura');

      expect(servicio.abrir).toHaveBeenCalledTimes(1);
      expect(servicio.abrir).toHaveBeenCalledWith(6500);
      expect(normal(q('.alerta.exito')?.textContent)).toBe('Abriste la caja.');
      expect(q('form.apertura')).toBeNull();
      expect(q('form.movimiento')).not.toBeNull();
    });

    it('abrir: propone el efectivo del último cierre y sin importe o con importe negativo no manda nada', async () => {
      await crear(sinCaja(7000), { modo });
      expect((campo('efectivo-inicial') as HTMLInputElement).value).toBe('7.000,00');

      await escribir('efectivo-inicial', '');
      await enviar('form.apertura');
      expect(normal(q('#efectivo-inicial-error')?.textContent)).toBe('Indicá el efectivo inicial.');
      expect(servicio.abrir).not.toHaveBeenCalled();
    });

    it('abrir: si otro usuario ya la abrió, muestra el mensaje del servidor y relee la caja', async () => {
      await crear(sinCaja(), { modo });
      servicio.abrir.mockReturnValue(rechazo('Ya hay una caja abierta.'));

      await enviar('form.apertura');

      expect(normal(q('p.error')?.textContent)).toBe('Ya hay una caja abierta.');
      expect(servicio.actual).toHaveBeenCalledTimes(2);
    });

    it('movimiento: manda tipo, importe y motivo literales, avisa y limpia el formulario', async () => {
      await crear(conCaja(), { modo });

      await escribir('tipo-movimiento', 'GASTO');
      await escribir('importe-movimiento', '20000');
      await escribir('motivo-movimiento', '  Flete de mercadería ');
      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).toHaveBeenCalledTimes(1);
      expect(servicio.registrarMovimiento).toHaveBeenCalledWith(4, { tipo: 'GASTO', importe: 20000, motivo: 'Flete de mercadería' });
      expect(normal(q('.alerta.exito')?.textContent)).toBe('Se cargó el gasto de $ 20.000,00.');
      expect((campo('importe-movimiento') as HTMLInputElement).value).toBe('');
      expect((campo('motivo-movimiento') as HTMLInputElement).value).toBe('');
    });

    it('movimiento: sin importe o sin motivo no manda nada y dice por qué', async () => {
      await crear(conCaja(), { modo });

      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).not.toHaveBeenCalled();
      expect(normal(q('#importe-movimiento-error')?.textContent)).toBe('El importe tiene que ser mayor a cero.');
      expect(normal(q('#motivo-movimiento-error')?.textContent)).toBe('Indicá el motivo.');
    });

    it('movimiento rechazado: muestra el mensaje y deja lo escrito sin tocar', async () => {
      await crear(conCaja(), { modo });
      servicio.registrarMovimiento.mockReturnValue(rechazo('La caja ya está cerrada.'));

      await escribir('importe-movimiento', '350');
      await escribir('motivo-movimiento', 'Cinta');
      await enviar('form.movimiento');

      expect(normal(q('p.error')?.textContent)).toBe('La caja ya está cerrada.');
      expect((campo('importe-movimiento') as HTMLInputElement).value).toBe('350');
      expect((campo('motivo-movimiento') as HTMLInputElement).value).toBe('Cinta');
    });

    it('retiro sin efectivo: el Dueño ve las cifras y «Registrar igual» manda el mismo pedido confirmado', async () => {
      await crear(conCaja(), { modo, dueno: true });
      servicio.registrarMovimiento.mockReturnValueOnce(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 422,
              error: {
                status: 422,
                message: 'No alcanza el efectivo.',
                motivo: 'EFECTIVO_INSUFICIENTE',
                efectivo: { disponible: 80000, faltante: 20000, puedeConfirmar: true },
              },
            }),
        ),
      );

      await escribir('tipo-movimiento', 'RETIRO');
      await escribir('importe-movimiento', '100000');
      await escribir('motivo-movimiento', 'Depósito');
      await enviar('form.movimiento');

      expect(normal(q('.falta-efectivo')?.textContent)).toBe(
        'No alcanza el efectivo. Hay $ 80.000,00 en efectivo y faltan $ 20.000,00: la caja va a quedar en negativo. Registrar igual Cancelar',
      );
      expect(servicio.registrarMovimiento).toHaveBeenCalledTimes(1);

      (Array.from(el().querySelectorAll('.falta-efectivo button')).find((b) => normal(b.textContent) === 'Registrar igual') as HTMLElement).click();
      await refrescar();

      expect(servicio.registrarMovimiento).toHaveBeenCalledTimes(2);
      expect(servicio.registrarMovimiento).toHaveBeenLastCalledWith(4, {
        tipo: 'RETIRO',
        importe: 100000,
        motivo: 'Depósito',
        confirmaSinEfectivo: true,
      });
      expect(q('.falta-efectivo')).toBeNull();
    });

    it('cierre: valida lo contado y no pide confirmación ni manda nada', async () => {
      await crear(conCaja(), { modo });
      await irAlCierre();

      await enviar('form.cierre');

      expect(normal(q('#efectivo-contado-error')?.textContent)).toBe('Indicá el efectivo contado.');
      expect(q('app-confirmacion')).toBeNull();
      expect(servicio.cerrar).not.toHaveBeenCalled();
    });

    it('cierre: confirma con el contado y manda el pedido; el esperado y la diferencia salen recién del cierre', async () => {
      await crear(conCaja(), { modo });
      await irAlCierre();
      expect(normal(q('form.cierre')?.textContent)).not.toContain('5.700,00');

      await escribir('efectivo-contado', '5650');
      await enviar('form.cierre');

      expect(normal(q('app-confirmacion h2')?.textContent)).toBe('¿Cerrar la caja con $ 5.650,00 contados?');
      expect(normal(q('app-confirmacion p')?.textContent)).toBe('Una vez cerrada no se puede reabrir ni editar.');
      expect(servicio.cerrar).not.toHaveBeenCalled();

      botonConfirmar().click();
      await refrescar();

      expect(servicio.cerrar).toHaveBeenCalledTimes(1);
      expect(servicio.cerrar).toHaveBeenCalledWith(4, 5650);
      expect(normal(q('.alerta.exito')?.textContent)).toBe('Cerraste la caja.');
      const resumen = normal(q('app-resumen-caja')?.textContent);
      expect(resumen).toContain('Efectivo esperado $ 5.700,00');
      expect(resumen).toContain('Efectivo contado $ 5.650,00');
      expect(resumen).toContain('Diferencia Faltan $ 50,00');
      expect(q('form.cierre')).toBeNull();
      expect((campo('efectivo-inicial') as HTMLInputElement).value).toBe('5.650,00');
    });

    it('cierre: cancelar la confirmación no cierra nada y deja lo contado', async () => {
      await crear(conCaja(), { modo });
      await irAlCierre();
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');

      botonCancelar().click();
      await refrescar();

      expect(servicio.cerrar).not.toHaveBeenCalled();
      expect(q('app-confirmacion')).toBeNull();
      expect((campo('efectivo-contado') as HTMLInputElement).value).toBe('5.700');
    });

    it('cierre rechazado: muestra el mensaje, relee la caja y conserva lo contado', async () => {
      await crear(conCaja(), { modo });
      servicio.cerrar.mockReturnValue(rechazo('La caja ya fue cerrada.'));
      await irAlCierre();
      await escribir('efectivo-contado', '5600');
      await enviar('form.cierre');

      botonConfirmar().click();
      await refrescar();

      expect(servicio.cerrar).toHaveBeenCalledTimes(1);
      expect(servicio.cerrar).toHaveBeenCalledWith(4, 5600);
      expect(normal(q('p.error')?.textContent)).toBe('La caja ya fue cerrada.');
      expect(q('app-confirmacion')).toBeNull();
      expect(servicio.actual).toHaveBeenCalledTimes(2);
      expect(q('app-resumen-caja')).toBeNull();
      await irAlCierre();
      expect((campo('efectivo-contado') as HTMLInputElement).value).toBe('5.600');
    });

    it('roles: el Vendedor no ve el retiro ni el resumen en vivo; el Dueño sí', async () => {
      await crear(conCaja(), { modo, dueno: false });
      expect(Array.from((campo('tipo-movimiento') as HTMLSelectElement).options).map((o) => o.value)).toEqual(['INGRESO', 'GASTO']);
      expect(normal(q('form.movimiento h2')?.textContent)).toBe('Ingresos y gastos');
      expect(q('app-resumen-caja')).toBeNull();
      expect(q('a.historial')).toBeNull();
      TestBed.resetTestingModule();

      await crear(conCaja(), { modo, dueno: true });
      expect(Array.from((campo('tipo-movimiento') as HTMLSelectElement).options).map((o) => o.value)).toEqual(['INGRESO', 'GASTO', 'RETIRO']);
      expect(normal(q('form.movimiento h2')?.textContent)).toBe('Ingresos, gastos y retiros');
      expect(normal(q('app-resumen-caja')?.textContent)).toContain('Efectivo esperado $ 5.700,00');
      expect(q('a.historial')?.getAttribute('href')).toBe('/caja/historial');
    });

    it('caja de otro día: el aviso de hoy, sin formulario de movimientos, y sólo deja cerrarla', async () => {
      await crear(conCaja(abierta({ estado: 'DE_OTRO_DIA', fechaApertura: '2026-10-14T08:00:00' })), { modo });

      expect(normal(q('.estado .alerta')?.textContent)).toBe(
        'La caja del 14/10 sigue abierta. Cerrala antes de vender o cargar movimientos.',
      );
      expect(q('form.movimiento')).toBeNull();
      expect(q('form.cierre')).not.toBeNull();
    });

    it('mientras el servidor responde, el botón de enviar queda apagado y vuelve a prenderse', async () => {
      await crear(sinCaja(1000), { modo });
      const respuesta = new Subject<CajaAbierta>();
      servicio.abrir.mockReturnValue(respuesta);

      await enviar('form.apertura');
      expect(q<HTMLButtonElement>('form.apertura button[type="submit"]')!.disabled).toBe(true);
      // Un segundo envío mientras tanto no repite el pedido.
      await enviar('form.apertura');
      expect(servicio.abrir).toHaveBeenCalledTimes(1);

      respuesta.next(abierta());
      respuesta.complete();
      await refrescar();
      expect(q('form.apertura')).toBeNull();
    });
  });

  // ---------------------------------------------------------------- estructura del celular
  describe('estructura en el celular', () => {
    it('Dueño: el ⋯ de la barra abre una hoja con «Historial de cajas» y lleva a /caja/historial', async () => {
      await crear(conCaja(), { dueno: true });
      const navegar = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const mas = q<HTMLButtonElement>('app-barra-acciones button.mas-caja')!;
      expect(mas.getAttribute('aria-label')).toBe('Más opciones de Caja');
      expect(q('app-hoja-inferior')).toBeNull();

      mas.click();
      await refrescar();

      expect(normal(q('app-hoja-inferior .hoja-inferior-titulo')?.textContent)).toBe('Caja');
      const fila = q<HTMLButtonElement>('app-hoja-inferior button.accion-hoja')!;
      expect(normal(fila.textContent)).toBe('Historial de cajas');
      fila.click();
      await refrescar();

      expect(navegar).toHaveBeenCalledWith(['/caja/historial']);
      expect(q('app-hoja-inferior')).toBeNull();
    });

    it('Dueño: Esc cierra la hoja del ⋯ sin ir a ningún lado', async () => {
      await crear(conCaja(), { dueno: true });
      const navegar = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      await tocar('button.mas-caja');

      tecla(q('app-hoja-inferior .hoja-inferior')!, 'Escape');
      await refrescar();

      expect(q('app-hoja-inferior')).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });

    it('Vendedor: no tiene el ⋯ (el historial es del Dueño)', async () => {
      await crear(conCaja(), { dueno: false });

      expect(q('button.mas-caja')).toBeNull();
      expect(q('app-hoja-inferior')).toBeNull();
    });

    it('con la caja abierta: «Cargar» va en un pie fijo, dentro del formulario y fuera de la fila de campos', async () => {
      await crear(conCaja());

      const cargar = q<HTMLButtonElement>('form.movimiento .caja-pie button.cargar')!;
      expect(normal(cargar.textContent)).toBe('Cargar');
      expect(cargar.type).toBe('submit');
      expect(q('form.movimiento .caja-pie')!.classList.contains('ficha-pie')).toBe(true);
      expect(q('form.movimiento .fila-campos button.cargar')).toBeNull();
      expect(el().querySelectorAll('button.cargar').length).toBe(1);
    });

    it('«Cargar» del pie manda el movimiento con los mismos literales', async () => {
      await crear(conCaja());
      await escribir('tipo-movimiento', 'GASTO');
      await escribir('importe-movimiento', '20000');
      await escribir('motivo-movimiento', 'Flete de mercadería');

      await tocar('.caja-pie button.cargar');

      expect(servicio.registrarMovimiento).toHaveBeenCalledWith(4, { tipo: 'GASTO', importe: 20000, motivo: 'Flete de mercadería' });
      expect(normal(q('.alerta.exito')?.textContent)).toBe('Se cargó el gasto de $ 20.000,00.');
    });

    it('con la caja abierta el estado dice quién abrió, cuándo y el efectivo inicial', async () => {
      await crear(conCaja());

      expect(normal(q('section.estado h2')?.textContent)).toBe('Caja abierta');
      expect(normal(q('section.estado .quien')?.textContent)).toBe('Abrió Ana · 16/10 08:00 · Efectivo inicial $ 5.000,00');
    });

    it('sin caja: «Abrir caja» va en el pie fijo del formulario y sigue diciendo por qué no se vende', async () => {
      await crear(sinCaja(7000));

      const abrir = q<HTMLButtonElement>('form.apertura .caja-pie button[type="submit"]')!;
      expect(normal(abrir.textContent)).toBe('Abrir caja');
      expect(q('form.apertura .fila-campos button[type="submit"]')).toBeNull();
      expect(normal(q('form.apertura .ayuda')?.textContent)).toBe(
        'Sin caja abierta no se puede vender. Se propone el efectivo contado en el último cierre.',
      );

      abrir.click();
      await refrescar();
      expect(servicio.abrir).toHaveBeenCalledWith(7000);
    });

    it('la fila «Cerrar la caja» abre la pantalla del cierre; antes, el formulario no está a la vista', async () => {
      await crear(conCaja());

      const fila = q<HTMLButtonElement>('button.fila-cerrar-caja')!;
      expect(normal(fila.textContent)).toBe('Cerrar la caja Contá el efectivo y cargalo');
      expect(q('form.cierre')!.classList.contains('cierre-celular')).toBe(true);
      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(false);

      fila.click();
      await refrescar();

      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(true);
    });

    it('la pantalla del cierre: «Volver», título, el aviso de hoy, el campo de contado y «Cerrar caja» en un pie fijo', async () => {
      await crear(conCaja());
      await irAlCierre();

      const barra = q('form.cierre > .ficha-barra')!;
      expect(barra.querySelector('button.ficha-volver')!.getAttribute('aria-label')).toBe('Volver');
      expect(normal(barra.querySelector('h2')?.textContent)).toBe('Cerrar la caja');
      expect(el().querySelectorAll('form.cierre h2').length).toBe(1);
      expect(normal(q('form.cierre .ayuda')?.textContent)).toBe('Contá el efectivo y cargalo. Al cerrar se muestra el resumen del día.');
      expect(q('form.cierre .fila-campos #efectivo-contado')).not.toBeNull();
      const cerrar = q<HTMLButtonElement>('form.cierre > .ficha-pie button[type="submit"]')!;
      expect(normal(cerrar.textContent)).toBe('Cerrar caja');
      expect(cerrar.classList.contains('primario')).toBe(true);
      expect(q('form.cierre .fila-campos button')).toBeNull();
    });

    it('al abrir el cierre el foco va a «Volver», no al campo (no sube el teclado)', async () => {
      await crear(conCaja());
      await irAlCierre();

      expect(document.activeElement).toBe(q('form.cierre .ficha-volver'));
      expect(document.activeElement instanceof HTMLInputElement).toBe(false);
    });

    it('«Volver» descarta lo escrito, no manda nada y el foco vuelve a la fila, no a un campo', async () => {
      await crear(conCaja());
      await irAlCierre();
      await escribir('efectivo-contado', '5700');

      await tocar('form.cierre .ficha-volver');

      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(false);
      expect(servicio.cerrar).not.toHaveBeenCalled();
      expect(q('app-confirmacion')).toBeNull();
      expect((campo('efectivo-contado') as HTMLInputElement).value).toBe('');
      expect(document.activeElement).toBe(q('button.fila-cerrar-caja'));
    });

    it('Esc en la pantalla del cierre hace lo mismo que «Volver»', async () => {
      await crear(conCaja());
      await irAlCierre();
      await escribir('efectivo-contado', '123');

      tecla(campo('efectivo-contado'), 'Escape');
      await refrescar();

      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(false);
      expect(servicio.cerrar).not.toHaveBeenCalled();
      expect((campo('efectivo-contado') as HTMLInputElement).value).toBe('');
      expect(document.activeElement).toBe(q('button.fila-cerrar-caja'));
    });

    it('con la confirmación abierta, Esc es de la confirmación y la pantalla del cierre sigue abierta', async () => {
      await crear(conCaja());
      await irAlCierre();
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');

      tecla(q('app-confirmacion [role="alertdialog"]')!, 'Escape');
      await refrescar();

      expect(q('app-confirmacion')).toBeNull();
      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(true);
      expect((campo('efectivo-contado') as HTMLInputElement).value).toBe('5.700');
      expect(servicio.cerrar).not.toHaveBeenCalled();
    });

    it('«Cerrar caja» del pie pide la confirmación y, confirmada, cierra con el contado', async () => {
      await crear(conCaja());
      await irAlCierre();
      await escribir('efectivo-contado', '5650');

      await tocar('form.cierre .ficha-pie button[type="submit"]');
      expect(normal(q('app-confirmacion h2')?.textContent)).toBe('¿Cerrar la caja con $ 5.650,00 contados?');
      botonConfirmar().click();
      await refrescar();

      expect(servicio.cerrar).toHaveBeenCalledWith(4, 5650);
      expect(q('form.cierre')).toBeNull();
      expect(q('button.fila-cerrar-caja')).toBeNull();
    });

    it('tras cerrar y volver a abrir una caja, la pantalla del cierre no queda abierta sola', async () => {
      await crear(conCaja());
      await irAlCierre();
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');
      botonConfirmar().click();
      await refrescar();

      await enviar('form.apertura');

      expect(q('form.cierre')!.classList.contains('cierre-abierto')).toBe(false);
    });

    it('con la caja de otro día también está la fila «Cerrar la caja»', async () => {
      await crear(conCaja(abierta({ estado: 'DE_OTRO_DIA', fechaApertura: '2026-10-14T08:00:00' })));

      expect(q('button.fila-cerrar-caja')).not.toBeNull();
      expect(q('form.movimiento')).toBeNull();
      expect(q('.caja-pie button.cargar')).toBeNull();
    });

    it('el Vendedor ve el mismo cierre ciego: la fila, la pantalla y ni una cifra del esperado', async () => {
      await crear(conCaja(), { dueno: false });
      await irAlCierre();

      expect(q('form.cierre.cierre-abierto')).not.toBeNull();
      expect(normal(el().textContent)).not.toContain('5.700,00');
      expect(q('app-resumen-caja')).toBeNull();
    });

    it('el aviso de falta de efectivo conserva su frase y sus dos acciones', async () => {
      await crear(conCaja(), { dueno: true });
      servicio.registrarMovimiento.mockReturnValueOnce(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 422,
              error: { status: 422, message: 'No alcanza.', motivo: 'EFECTIVO_INSUFICIENTE', efectivo: { disponible: 80000, faltante: 20000, puedeConfirmar: true } },
            }),
        ),
      );
      await escribir('importe-movimiento', '100000');
      await escribir('motivo-movimiento', 'Depósito');
      await tocar('.caja-pie button.cargar');

      const botones = Array.from(el().querySelectorAll('.falta-efectivo button')).map((b) => normal(b.textContent));
      expect(botones).toEqual(['Registrar igual', 'Cancelar']);
      (el().querySelector('.falta-efectivo .secundario') as HTMLElement).click();
      await refrescar();
      expect(q('.falta-efectivo')).toBeNull();
      expect(servicio.registrarMovimiento).toHaveBeenCalledTimes(1);
    });
  });

  // ---------------------------------------------------------------- escritorio y tablet no cambian
  describe.each<Modo>(['escritorio', 'tablet'])('sin nada del celular (%s)', (modo) => {
    const propios = '.ficha-barra, .ficha-pie, .caja-pie, .fila-cerrar-caja, button.mas-caja, app-hoja-inferior, .ficha-volver';

    it('con la caja abierta (Dueño): «Cargar» y «Cerrar caja» van en su fila de campos y no hay ⋯, pie ni fila de cierre', async () => {
      await crear(conCaja(), { modo, dueno: true });

      expect(el().querySelectorAll(propios).length).toBe(0);
      expect(q('form.movimiento .fila-campos button.cargar')).not.toBeNull();
      expect(normal(q('form.cierre .fila-campos button[type="submit"]')?.textContent)).toBe('Cerrar caja');
      expect(q('form.cierre .fila-campos button[type="submit"]')!.classList.contains('secundario')).toBe(true);
      expect(q('form.cierre .fila-campos button[type="submit"]')!.classList.contains('primario')).toBe(false);
      expect(q('form.cierre')!.className).not.toContain('cierre-');
      expect(normal(q('form.cierre > h2')?.textContent)).toBe('Cerrar la caja');
      expect(q('a.historial')).not.toBeNull();
    });

    it('sin caja: «Abrir caja» va en su fila de campos', async () => {
      await crear(sinCaja(7000), { modo });

      expect(el().querySelectorAll(propios).length).toBe(0);
      expect(normal(q('form.apertura .fila-campos button[type="submit"]')?.textContent)).toBe('Abrir caja');
    });

    it('el Vendedor no ve nada nuevo', async () => {
      await crear(conCaja(), { modo, dueno: false });

      expect(el().querySelectorAll(propios).length).toBe(0);
    });
  });
});
