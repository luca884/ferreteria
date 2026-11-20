import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaDatosFiscales } from './datos-fiscales';
import { DatosFiscales, DatosFiscalesRequest, DatosFiscalesService } from './datos-fiscales.service';

describe('PaginaDatosFiscales', () => {
  let fixture: ComponentFixture<PaginaDatosFiscales>;
  let obtener: ReturnType<typeof vi.fn>;
  let guardar: ReturnType<typeof vi.fn>;
  let refrescarSesion: ReturnType<typeof vi.fn>;
  let volverAtras: ReturnType<typeof vi.fn>;

  const guardados: DatosFiscales = {
    razonSocial: 'Tornillo SA',
    cuit: '30-71234567-1',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    domicilioComercial: 'Av. Luro 3200, Mar del Plata',
    inicioActividades: '2015-03-01',
    ingresosBrutos: '901-123456-7',
  };

  async function crear(dueno = true, carga = vi.fn(() => of(guardados))) {
    obtener = carga;
    guardar = vi.fn((pedido: DatosFiscalesRequest) => of({ ...pedido, cuit: guardados.cuit }));
    refrescarSesion = vi.fn(() => of({}));
    volverAtras = vi.fn();
    await TestBed.configureTestingModule({
      imports: [PaginaDatosFiscales],
      providers: [
        { provide: DatosFiscalesService, useValue: { obtener, guardar } },
        { provide: AuthService, useValue: { esDueno: signal(dueno), refrescarSesion } },
        { provide: Location, useValue: { back: volverAtras } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PaginaDatosFiscales);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const campo = (nombre: string) => el().querySelector<HTMLInputElement | HTMLSelectElement>(`[name="${nombre}"]`)!;

  async function escribir(nombre: string, valor: string) {
    const control = campo(nombre);
    control.value = valor;
    control.dispatchEvent(new Event(control instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await refrescar();
  }

  it('el título va en la barra y muestra los datos guardados, con el CUIT a la vista y sin poder editarlo', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Datos de la ferretería');
    expect(campo('razonSocial').value).toBe('Tornillo SA');
    expect(campo('condicionIva').value).toBe('RESPONSABLE_INSCRIPTO');
    expect(campo('domicilioComercial').value).toBe('Av. Luro 3200, Mar del Plata');
    expect(campo('inicioActividades').value).toBe('2015-03-01');
    expect(campo('ingresosBrutos').value).toBe('901-123456-7');
    expect(texto('.cuit')).toContain('30-71234567-1');
    expect(el().querySelector('[name="cuit"]')).toBeNull();
  });

  it('explica que son los datos de la factura y que el domicilio y el inicio son los del local donde se factura', async () => {
    await crear();

    expect(texto('p.ayuda')).toContain('Son los datos que lleva cada factura');
    expect(texto('p.ayuda')).toContain('del local donde se factura');
    expect(texto('.cuit')).toContain('No se puede cambiar');
    expect(texto('#ingresosBrutos-pista')).toContain('Convenio Multilateral, Exento o No contribuyente');
  });

  it('una ferretería sin datos fiscales muestra los campos vacíos', async () => {
    await crear(true, vi.fn(() => of({ ...guardados, domicilioComercial: null, inicioActividades: null, ingresosBrutos: null })));

    expect(campo('domicilioComercial').value).toBe('');
    expect(campo('inicioActividades').value).toBe('');
    expect(campo('ingresosBrutos').value).toBe('');
  });

  it('guardar manda los datos recortados, sin el CUIT y con los vacíos en null; confirma y refresca la sesión', async () => {
    await crear();

    await escribir('razonSocial', '  Tuerca SRL ');
    await escribir('condicionIva', 'MONOTRIBUTISTA');
    await escribir('domicilioComercial', '   ');
    await escribir('inicioActividades', '');
    await escribir('ingresosBrutos', ' Convenio Multilateral 901-1 ');
    await enviar();

    expect(guardar).toHaveBeenCalledWith({
      razonSocial: 'Tuerca SRL',
      condicionIva: 'MONOTRIBUTISTA',
      domicilioComercial: null,
      inicioActividades: null,
      ingresosBrutos: 'Convenio Multilateral 901-1',
    });
    expect(texto('.alerta.exito')).toBe('Se guardaron los datos de la ferretería.');
    expect(refrescarSesion).toHaveBeenCalledTimes(1);
    expect(campo('razonSocial').value).toBe('Tuerca SRL');
  });

  it('sin razón social no manda nada y lo avisa en el campo', async () => {
    await crear();

    await escribir('razonSocial', '   ');
    await enviar();

    expect(guardar).not.toHaveBeenCalled();
    expect(texto('#razonSocial-error')).toBe('La razón social es obligatoria.');
  });

  it('un inicio de actividades futuro no se manda y se avisa en el campo', async () => {
    await crear();

    await escribir('inicioActividades', '2999-01-01');
    await enviar();

    expect(guardar).not.toHaveBeenCalled();
    expect(texto('#inicioActividades-error')).toBe('El inicio de actividades no puede ser una fecha futura.');
  });

  it('un inicio de actividades anterior al 1/1/1900 no se manda; el 1/1/1900 sí', async () => {
    await crear();

    await escribir('inicioActividades', '1899-12-31');
    await enviar();
    expect(guardar).not.toHaveBeenCalled();
    expect(texto('#inicioActividades-error')).toBe('El inicio de actividades no puede ser anterior al 1/1/1900.');

    await escribir('inicioActividades', '1900-01-01');
    await enviar();
    expect(guardar).toHaveBeenLastCalledWith(expect.objectContaining({ inicioActividades: '1900-01-01' }));
  });

  it('Ingresos Brutos entra con el Convenio Multilateral escrito entero; más de 50 caracteres no se manda', async () => {
    await crear();

    await escribir('ingresosBrutos', 'Convenio Multilateral 901-123456-7');
    await enviar();
    expect(guardar).toHaveBeenLastCalledWith(
      expect.objectContaining({ ingresosBrutos: 'Convenio Multilateral 901-123456-7' }),
    );

    guardar.mockClear();
    await escribir('ingresosBrutos', 'x'.repeat(51));
    await enviar();
    expect(guardar).not.toHaveBeenCalled();
    expect(texto('#ingresosBrutos-error')).toBe('Ingresos Brutos admite hasta 50 caracteres.');
  });

  it('lo que rechaza el servidor se lista por campo, y un error sin campos se muestra entero', async () => {
    await crear();
    guardar.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Datos inválidos', errors: { ingresosBrutos: 'Ingresos Brutos admite hasta 50 caracteres.' } },
          }),
      ),
    );
    await enviar();
    expect(texto('.errores-campos')).toContain('Ingresos Brutos admite hasta 50 caracteres.');
    expect(refrescarSesion).not.toHaveBeenCalled();

    guardar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500, error: {} })));
    await enviar();
    expect(texto('p.error')).toBe('No se pudieron guardar los datos de la ferretería.');
  });

  it('si la carga falla, avisa y deja reintentar', async () => {
    await crear(true, vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))));

    expect(texto('.error')).toContain('No se pudieron cargar los datos de la ferretería.');
    obtener.mockReturnValue(of(guardados));
    el().querySelector<HTMLButtonElement>('.reintentar')!.click();
    await refrescar();

    expect(campo('razonSocial').value).toBe('Tornillo SA');
  });

  describe('datos para facturar vacíos (bugs-relevamiento-celular, S8 y S12)', () => {
    const avisos = () =>
      [...el().querySelectorAll('.campo-aviso')].map((a) => ({ id: a.id, texto: a.textContent?.trim() }));
    const sinDatos = { ...guardados, domicilioComercial: null, inicioActividades: null, ingresosBrutos: null };

    it('con los datos completos no hay avisos', async () => {
      await crear();

      expect(avisos()).toEqual([]);
    });

    it('avisa debajo de cada campo vacío, sin bloquear el guardado, y el aviso se va al completarlo y guardar', async () => {
      await crear(true, vi.fn(() => of(sinDatos)));

      expect(avisos()).toEqual([
        { id: 'ingresosBrutos-aviso', texto: 'Completalo para poder facturar.' },
        { id: 'domicilioComercial-aviso', texto: 'Completalo para poder facturar.' },
        { id: 'inicioActividades-aviso', texto: 'Completalo para poder facturar.' },
      ]);
      expect(campo('domicilioComercial').getAttribute('aria-describedby')).toBe('domicilioComercial-aviso');

      await enviar();
      expect(guardar).toHaveBeenCalledTimes(1);

      await escribir('domicilioComercial', 'Av. Luro 3200');
      await enviar();

      expect(guardar).toHaveBeenLastCalledWith(expect.objectContaining({ domicilioComercial: 'Av. Luro 3200' }));
      expect(avisos().map((a) => a.id)).toEqual(['ingresosBrutos-aviso', 'inicioActividades-aviso']);
    });
  });

  it('el Vendedor ve el aviso y no pide los datos', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Solo el Dueño puede ver y editar los datos de la ferretería.');
    expect(obtener).not.toHaveBeenCalled();
    expect(el().querySelector('form')).toBeNull();
  });

  /* movil-sectores T3 (S2, S7): «Guardar» queda fijo abajo en el celular; las medidas se hacen en el navegador a 390 px. */
  describe('formulario en el celular', () => {

  /** Las reglas (de este componente) cuyo selector coincide, con la consulta de medios que las envuelve. */
  function reglasQue(patron: RegExp): { consulta: string; selector: string }[] {
    const reglas: { consulta: string; selector: string }[] = [];
    const recorrer = (lista: CSSRuleList, consulta: string) =>
      Array.from(lista).forEach((regla) => {
        const media = (regla as CSSMediaRule).media;
        if (media && (regla as CSSMediaRule).cssRules) {
          recorrer((regla as CSSMediaRule).cssRules, [consulta, media.mediaText].filter(Boolean).join(' '));
        } else if (patron.test((regla as CSSStyleRule).selectorText ?? '')) {
          reglas.push({ consulta, selector: (regla as CSSStyleRule).selectorText });
        }
      });
    Array.from(document.styleSheets).forEach((hoja) => {
      try {
        recorrer(hoja.cssRules, '');
      } catch {
        /* una hoja ilegible no es la del componente */
      }
    });
    return reglas;
  }

    it('«Guardar» va en el pie fijo de la ficha, junto a «Cancelar», y sigue siendo el envío del formulario', async () => {
      await crear();

      const pie = el().querySelector('form.fiscales > .acciones-form')!;
      expect(pie.classList).toContain('ficha-pie');
      expect(pie.classList).not.toContain('en-pagina');
      expect(Array.from(pie.querySelectorAll('button')).map((b) => b.textContent!.trim())).toEqual(['Guardar', 'Cancelar']);
      expect(pie.querySelector<HTMLButtonElement>('button[type="submit"]')!.textContent!.trim()).toBe('Guardar');

      await enviar();
      expect(guardar).toHaveBeenCalledTimes(1);
    });

    it('el CUIT se ve como un campo de sólo lectura, con su pista, y no se puede editar', async () => {
      await crear();

      const cuit = el().querySelector('.campo.cuit')!;
      expect(cuit.querySelector('.valor')!.textContent!.trim()).toBe('30-71234567-1');
      expect(cuit.querySelector('.pista')!.textContent!.trim()).toBe('No se puede cambiar.');
      expect(cuit.querySelector('input, select, textarea')).toBeNull();
    });

    it('el título de cada sección y el CUIT de sólo lectura se ajustan sólo en el celular', async () => {
      await crear();

      const delCelular = reglasQue(/legend|\.cuit.*\.valor/).filter((r) => r.consulta.includes('max-width: 639.98px'));
      expect(delCelular.some((r) => /legend/.test(r.selector))).toBe(true);
      expect(delCelular.some((r) => /\.cuit.*\.valor/.test(r.selector))).toBe(true);
    });

  /*
   * movil-sectores T3 (reabierta, L8): en el celular Datos de la ferretería son DOS pantallas como el artboard (Emisor y
   * Local donde se factura), con la flecha «Volver» y el pie fijo, y el aviso de arriba «Completalo para poder facturar.».
   * Es un solo formulario: las dos tarjetas están siempre en el DOM (el escritorio las ve juntas) y el CSS del celular deja
   * la que tiene `.activa`. Vitest no evalúa media queries; las medidas se hacen en el navegador a 390 px.
   */
  describe('dos pantallas (celular)', () => {
    const secciones = () => Array.from(el().querySelectorAll<HTMLElement>('form.fiscales > fieldset.seccion'));
    const activas = () => secciones().filter((s) => s.classList.contains('activa')).map((s) => s.querySelector('legend')!.textContent!.trim());
    const volver = () => el().querySelector<HTMLButtonElement>('.ficha-barra .ficha-volver')!;
    const irALocal = () => el().querySelector<HTMLButtonElement>('button.ir-local')!;
    const cancelar = () => el().querySelector<HTMLButtonElement>('.ficha-pie button.cancelar-ficha')!;
    const incompleto = () => el().querySelector('.aviso-incompleto');

    it('arranca en Emisor, con la barra de la ficha (flecha «Volver» y el título) y las dos tarjetas en el DOM', async () => {
      await crear();

      expect(secciones().map((s) => s.querySelector('legend')!.textContent!.trim())).toEqual(['Emisor', 'Local donde se factura']);
      expect(activas()).toEqual(['Emisor']);
      expect(volver().getAttribute('aria-label')).toBe('Volver');
      expect(texto('.ficha-barra h2')).toBe('Datos de la ferretería');
      // La ficha cubre el título de la barra de acciones: sigue ahí para el escritorio.
      expect(texto('app-barra-acciones h1')).toBe('Datos de la ferretería');
    });

    it('Emisor lleva una fila para pasar a Local, que dice cuál es el domicilio', async () => {
      await crear();

      expect(irALocal().textContent).toContain('Local donde se factura');
      expect(irALocal().textContent).toContain('Av. Luro 3200, Mar del Plata');
      expect(irALocal().closest('form.fiscales')).not.toBeNull();
    });

    it('la fila abre Local y «Volver» regresa a Emisor sin perder lo escrito ni guardar', async () => {
      await crear();
      await escribir('razonSocial', 'Tuerca SRL');

      irALocal().click();
      await refrescar();
      expect(activas()).toEqual(['Local donde se factura']);
      expect(el().querySelector('button.ir-local')).toBeNull();
      expect(document.activeElement).toBe(volver());
      await escribir('domicilioComercial', 'San Martín 1500');

      volver().click();
      await refrescar();

      expect(activas()).toEqual(['Emisor']);
      expect(volverAtras).not.toHaveBeenCalled();
      expect(guardar).not.toHaveBeenCalled();
      expect(campo('razonSocial').value).toBe('Tuerca SRL');
      expect(campo('domicilioComercial').value).toBe('San Martín 1500');
      expect(irALocal().textContent).toContain('San Martín 1500');
      // El foco vuelve a la fila (se comprueba en el navegador: en Vitest la fila está escondida por el CSS de escritorio).
    });

    it('«Volver» en Emisor sale de la pantalla (vuelve a donde estaba el usuario) y descarta lo escrito', async () => {
      await crear();
      await escribir('razonSocial', 'Tuerca SRL');

      volver().click();
      await refrescar();

      expect(volverAtras).toHaveBeenCalledTimes(1);
      expect(guardar).not.toHaveBeenCalled();
      expect(campo('razonSocial').value).toBe('Tornillo SA');
    });

    it('«Cancelar» descarta lo escrito en las dos pantallas, vuelve a Emisor y sale', async () => {
      await crear();
      await escribir('razonSocial', 'Tuerca SRL');
      irALocal().click();
      await refrescar();
      await escribir('domicilioComercial', 'San Martín 1500');

      cancelar().click();
      await refrescar();

      expect(volverAtras).toHaveBeenCalledTimes(1);
      expect(guardar).not.toHaveBeenCalled();
      expect(activas()).toEqual(['Emisor']);
      expect(campo('razonSocial').value).toBe('Tornillo SA');
      expect(campo('domicilioComercial').value).toBe('Av. Luro 3200, Mar del Plata');
    });

    it('«Guardar» desde Local manda los datos de las dos pantallas en un solo pedido', async () => {
      await crear();
      await escribir('razonSocial', 'Tuerca SRL');
      irALocal().click();
      await refrescar();
      await escribir('domicilioComercial', 'San Martín 1500');

      await enviar();

      expect(guardar).toHaveBeenCalledTimes(1);
      expect(guardar).toHaveBeenCalledWith(
        expect.objectContaining({ razonSocial: 'Tuerca SRL', domicilioComercial: 'San Martín 1500' }),
      );
      expect(texto('.alerta.exito')).toBe('Se guardaron los datos de la ferretería.');
    });

    it('si lo que falta está en la otra pantalla, «Guardar» lleva a la pantalla del error y no manda nada', async () => {
      await crear();
      await escribir('razonSocial', '   ');
      irALocal().click();
      await refrescar();
      expect(activas()).toEqual(['Local donde se factura']);

      await enviar();

      expect(guardar).not.toHaveBeenCalled();
      expect(activas()).toEqual(['Emisor']);
      expect(texto('#razonSocial-error')).toBe('La razón social es obligatoria.');

      await escribir('razonSocial', 'Tuerca SRL');
      irALocal().click();
      await refrescar();
      await escribir('inicioActividades', '2999-01-01');
      volver().click();
      await refrescar();
      await enviar();

      expect(guardar).not.toHaveBeenCalled();
      expect(activas()).toEqual(['Local donde se factura']);
      expect(texto('#inicioActividades-error')).toBe('El inicio de actividades no puede ser una fecha futura.');
    });

    it('el aviso «Completalo para poder facturar.» sale con la misma condición que los avisos de cada campo', async () => {
      await crear();
      expect(incompleto()).toBeNull();

      for (const vacio of ['ingresosBrutos', 'domicilioComercial', 'inicioActividades'] as const) {
        TestBed.resetTestingModule();
        await crear(true, vi.fn(() => of({ ...guardados, [vacio]: null })));

        expect(incompleto()).not.toBeNull();
        expect(incompleto()!.textContent!.trim()).toBe('Completalo para poder facturar.');
        expect(el().querySelector(`#${vacio}-aviso`)).not.toBeNull();
      }
    });

    it('el aviso va arriba de la tarjeta de Emisor, dentro de la ficha, y se va al guardar los datos que faltaban', async () => {
      await crear(true, vi.fn(() => of({ ...guardados, ingresosBrutos: null })));

      const aviso = incompleto()!;
      expect(aviso.closest('.ficha-flujo')).not.toBeNull();
      expect(aviso.compareDocumentPosition(secciones()[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

      await escribir('ingresosBrutos', 'Exento');
      expect(incompleto()).not.toBeNull(); // sigue: lo guardado todavía no lo tiene
      await enviar();

      expect(incompleto()).toBeNull();
    });

    it('un guardado que sigue sin los datos deja el aviso', async () => {
      await crear(true, vi.fn(() => of({ ...guardados, domicilioComercial: null })));

      await escribir('razonSocial', 'Tuerca SRL');
      await enviar();

      expect(incompleto()).not.toBeNull();
    });

    it('el aviso, la fila y la barra de la ficha son sólo del celular (cada uno con su regla base que lo esconde)', async () => {
      await crear();

      const reglas = reglasQue(/ir-local|aviso-incompleto|pantalla-activa|\.activa/);
      expect(reglas.some((r) => /aviso-incompleto/.test(r.selector) && r.consulta === '')).toBe(true);
      expect(reglas.some((r) => /ir-local/.test(r.selector) && r.consulta === '')).toBe(true);
      expect(reglas.some((r) => /aviso-incompleto/.test(r.selector) && r.consulta.includes('max-width: 639.98px'))).toBe(true);
      expect(reglas.some((r) => /ir-local/.test(r.selector) && r.consulta.includes('max-width: 639.98px'))).toBe(true);
      for (const regla of reglas.filter((r) => /\.activa/.test(r.selector))) {
        expect(regla.consulta).toContain('max-width: 639.98px');
      }
    });
  });
  });
});
