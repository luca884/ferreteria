import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Articulo, Oferta } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { OfertaArticulo } from './oferta-articulo';

registerLocaleData(localeEsAr);

const NBSP = ' ';

describe('OfertaArticulo', () => {
  const articulo = (oferta: Oferta | null = null): Articulo => ({
    id: 7,
    codigoInterno: 'TAL-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Taladro',
    rubroId: null,
    proveedorId: null,
    costo: 100,
    moneda: 'ARS',
    margenPorcentaje: 30,
    alicuotaIva: 'IVA_21',
    precioFinal: 1000,
    unidad: 'UNIDAD',
    stockActual: 5,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
    version: 3,
    oferta,
  });
  const vigente: Oferta = { tipo: 'PRECIO_FIJO', valor: 800, hasta: null, vigente: true, precioOferta: 800 };

  let fixture: ComponentFixture<OfertaArticulo>;
  let servicio: { ponerOferta: ReturnType<typeof vi.fn>; quitarOferta: ReturnType<typeof vi.fn> };
  let emitidos: Articulo[];

  async function crear(a: Articulo, editable = true, celular = false) {
    servicio = { ponerOferta: vi.fn(), quitarOferta: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [OfertaArticulo],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(OfertaArticulo);
    fixture.componentRef.setInput('articulo', a);
    fixture.componentRef.setInput('editable', editable);
    fixture.componentRef.setInput('celular', celular);
    emitidos = [];
    fixture.componentInstance.cambiado.subscribe((x) => emitidos.push(x));
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const boton = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === texto);

  async function escribir(selector: string, valor: string) {
    const input = el().querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  it('el Dueño carga un precio fijo, ve el precio que resulta y guarda con la versión', async () => {
    await crear(articulo());
    const guardado = articulo(vigente);
    servicio.ponerOferta.mockReturnValue(of(guardado));

    await escribir('#oferta-valor', '800');
    expect(texto('.precio-resultante')).toBe(`Precio de oferta: $${NBSP}800,00`);
    boton('Guardar oferta')!.click();
    await refrescar();

    expect(servicio.ponerOferta).toHaveBeenCalledWith(7, {
      tipo: 'PRECIO_FIJO',
      valor: 800,
      hasta: null,
      version: 3,
    });
    expect(emitidos).toEqual([guardado]);
  });

  it('el precio de oferta y el descuento en $ son campos de plata con máscara: 1500 se ve 1.500 y al salir 1.500,00', async () => {
    await crear(articulo());
    const valor = () => el().querySelector<HTMLInputElement>('#oferta-valor')!;

    expect(valor().closest('app-campo-moneda')).not.toBeNull();
    await escribir('#oferta-valor', '500');
    expect(valor().value).toBe('500');
    await escribir('#oferta-tipo', 'DESCUENTO_MONTO');
    expect(valor().closest('app-campo-moneda')).not.toBeNull();
    await escribir('#oferta-valor', '1500');
    expect(valor().value).toBe('1.500');
    valor().dispatchEvent(new Event('blur'));
    expect(valor().value).toBe('1.500,00');
  });

  it('el descuento en % sigue siendo un número común, sin signo de plata', async () => {
    await crear(articulo());

    await escribir('#oferta-tipo', 'DESCUENTO_PORCENTAJE');

    const valor = el().querySelector<HTMLInputElement>('#oferta-valor')!;
    expect(valor.type).toBe('number');
    expect(valor.closest('app-campo-moneda')).toBeNull();
  });

  it('un descuento en % con fecha de fin muestra el precio redondeado como el servidor', async () => {
    await crear(articulo());
    servicio.ponerOferta.mockReturnValue(of(articulo(vigente)));

    await escribir('#oferta-tipo', 'DESCUENTO_PORCENTAJE');
    await escribir('#oferta-valor', '12.5');
    await escribir('#oferta-hasta', '2026-11-30');
    expect(texto('.precio-resultante')).toBe(`Precio de oferta: $${NBSP}875,00`);
    boton('Guardar oferta')!.click();
    await refrescar();

    expect(servicio.ponerOferta).toHaveBeenCalledWith(7, {
      tipo: 'DESCUENTO_PORCENTAJE',
      valor: 12.5,
      hasta: '2026-11-30',
      version: 3,
    });
  });

  it('si el valor no baja el precio lo avisa antes de guardar', async () => {
    await crear(articulo());

    await escribir('#oferta-valor', '1000');

    expect(texto('.precio-resultante')).toBe('Con ese valor el precio no baja.');
  });

  it('sin valor no se puede guardar: el botón queda apagado con aria-disabled (S5)', async () => {
    await crear(articulo());

    const guardar = boton('Guardar oferta')!;
    expect(guardar.getAttribute('aria-disabled')).toBe('true');
    expect(guardar.hasAttribute('disabled')).toBe(false);
    expect(boton('Sacar oferta')).toBeUndefined();
  });

  it('con el botón apagado, el clic trae el motivo a la vista y no guarda (S5)', async () => {
    await crear(articulo());

    boton('Guardar oferta')!.click();
    await refrescar();

    // Por su contenedor: la ficha de artículo también puede tener un motivo a la vista.
    const motivo = el().querySelector('.oferta-articulo .motivo-accion');
    expect(motivo?.getAttribute('role')).toBe('status');
    expect(motivo?.textContent?.trim()).toBe('Completá el porcentaje o el precio de oferta.');
    expect(servicio.ponerOferta).not.toHaveBeenCalled();
  });

  it('con el motivo a la vista, completar el valor lo hace desaparecer sin volver a tocar el botón (S5)', async () => {
    await crear(articulo());

    boton('Guardar oferta')!.click();
    await refrescar();
    expect(el().querySelector('.oferta-articulo .motivo-accion')).not.toBeNull();

    await escribir('#oferta-valor', '800');

    expect(el().querySelector('.oferta-articulo .motivo-accion')).toBeNull();
    expect(boton('Guardar oferta')!.getAttribute('aria-disabled')).toBeNull();
    expect(boton('Guardar oferta')!.hasAttribute('disabled')).toBe(false);
  });

  it('con una oferta vigente muestra el resumen, arranca con sus datos y la puede sacar', async () => {
    await crear(articulo({ ...vigente, hasta: '2026-11-30' }));
    const sinOferta = articulo(null);
    servicio.quitarOferta.mockReturnValue(of(sinOferta));

    expect(texto('.resumen-oferta')).toBe(
      `En oferta a $${NBSP}800,00 (precio normal $${NBSP}1.000,00) hasta el 30/11/2026.`,
    );
    expect(el().querySelector<HTMLInputElement>('#oferta-valor')!.value).toBe('800,00');
    boton('Sacar oferta')!.click();
    await refrescar();

    expect(servicio.quitarOferta).toHaveBeenCalledWith(7);
    expect(emitidos).toEqual([sinOferta]);
  });

  it('una oferta vencida lo dice en el resumen', async () => {
    await crear(articulo({ ...vigente, hasta: '2020-01-31', vigente: false, precioOferta: null }));

    expect(texto('.resumen-oferta')).toBe('La oferta venció el 31/01/2020.');
  });

  it('una oferta vigente por fecha que ya no baja el precio lo explica', async () => {
    await crear(articulo({ ...vigente, vigente: false, precioOferta: null }));

    expect(texto('.resumen-oferta')).toBe('La oferta no baja el precio normal de hoy, así que no se aplica.');
  });

  it('muestra cada error del servidor debajo de su campo', async () => {
    await crear(articulo());
    servicio.ponerOferta.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Datos inválidos', errors: { hasta: 'La fecha de fin no puede ser anterior a hoy.' } },
          }),
      ),
    );

    await escribir('#oferta-valor', '800');
    boton('Guardar oferta')!.click();
    await refrescar();

    expect(texto('#oferta-hasta-error')).toBe('La fecha de fin no puede ser anterior a hoy.');
    expect(emitidos).toEqual([]);
  });

  it('un 409 muestra el mensaje del servidor', async () => {
    await crear(articulo());
    servicio.ponerOferta.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Otro cambio modificó este artículo. Recargá y volvé a intentar.' },
          }),
      ),
    );

    await escribir('#oferta-valor', '800');
    boton('Guardar oferta')!.click();
    await refrescar();

    expect(texto('.oferta-articulo [role="alert"]')).toBe(
      'Otro cambio modificó este artículo. Recargá y volvé a intentar.',
    );
  });

  it('el Vendedor ve solo el resumen, sin formulario', async () => {
    await crear(articulo(vigente), false);

    expect(texto('.resumen-oferta')).toContain('En oferta a');
    expect(el().querySelector('#oferta-valor')).toBeNull();
    expect(boton('Guardar oferta')).toBeUndefined();
  });

  it('el Vendedor sin oferta no ve la sección', async () => {
    await crear(articulo(), false);

    expect(el().querySelector('.oferta-articulo')).toBeNull();
  });

  /*
   * movil-sectores T5 (5d; S1, S2, S5, S7): en la ficha del celular (`[celular]`) la oferta es una tarjeta plegable y el
   * tipo son tres botones de 44 px que escriben el mismo control que el select; el guardado y el resto no cambian. El
   * escritorio no cambia. Vitest no lee oferta-articulo.css: las medidas se ven en el navegador a 390 px.
   */
  describe('en el celular (movil-sectores T5, 5d)', () => {
    const cabecera = () => el().querySelector<HTMLButtonElement>('h2 .seccion-cabecera')!;
    const segmentos = () => Array.from(el().querySelectorAll<HTMLButtonElement>('.tipo-segmentado button'));
    const marcado = () => segmentos().filter((b) => b.getAttribute('aria-checked') === 'true').map((b) => b.textContent!.trim());
    async function abrir() {
      cabecera().click();
      await refrescar();
    }

    it('es una tarjeta plegable: arranca cerrada, sólo con su cabecera, y se abre y se cierra al tocarla', async () => {
      await crear(articulo(), true, true);

      expect(el().querySelector('.oferta-articulo')!.classList).toContain('oferta-celular');
      expect(texto('h2')).toBe('Oferta');
      expect(cabecera().getAttribute('aria-expanded')).toBe('false');
      expect(el().querySelector('form')).toBeNull();
      expect(boton('Guardar oferta')).toBeUndefined();

      await abrir();
      expect(cabecera().getAttribute('aria-expanded')).toBe('true');
      expect(el().querySelector('form')).not.toBeNull();
      expect(boton('Guardar oferta')).toBeDefined();

      await abrir();
      expect(cabecera().getAttribute('aria-expanded')).toBe('false');
      expect(el().querySelector('form')).toBeNull();
    });

    it('plegada muestra el precio de la oferta vigente; sin oferta, nada', async () => {
      await crear(articulo(vigente), true, true);
      expect(texto('.seccion-dato')).toBe(`$${NBSP}800,00`);

      TestBed.resetTestingModule();
      await crear(articulo(), true, true);
      expect(el().querySelector('.seccion-dato')).toBeNull();
    });

    it('el tipo son tres botones con los textos del select; no hay select', async () => {
      await crear(articulo(), true, true);
      await abrir();

      expect(el().querySelector('#oferta-tipo')).toBeNull();
      expect(el().querySelector('.tipo-segmentado')!.getAttribute('role')).toBe('radiogroup');
      expect(segmentos().map((b) => b.textContent!.trim())).toEqual(['Precio fijo', 'Descuento en %', 'Descuento en $']);
      expect(segmentos().every((b) => b.getAttribute('role') === 'radio' && b.type === 'button')).toBe(true);
      expect(marcado()).toEqual(['Precio fijo']);
    });

    it('cada botón elige el mismo tipo que el select: cambia el campo del valor y el precio que resulta', async () => {
      await crear(articulo(), true, true);
      await abrir();
      const valor = () => el().querySelector<HTMLInputElement>('#oferta-valor')!;

      segmentos()[1].click();
      await refrescar();
      expect(marcado()).toEqual(['Descuento en %']);
      expect(valor().type).toBe('number');
      expect(texto('label[for="oferta-valor"]')).toContain('Descuento (%)');
      await escribir('#oferta-valor', '10');
      expect(texto('.precio-resultante')).toBe(`Precio de oferta: $${NBSP}900,00`);

      segmentos()[2].click();
      await refrescar();
      expect(marcado()).toEqual(['Descuento en $']);
      expect(valor().closest('app-campo-moneda')).not.toBeNull();
      expect(texto('label[for="oferta-valor"]')).toContain('Descuento ($)');
    });

    it('guardar con un tipo elegido con los botones manda lo mismo que con el select', async () => {
      await crear(articulo(), true, true);
      await abrir();
      servicio.ponerOferta.mockReturnValue(of(articulo(vigente)));

      segmentos()[1].click();
      await refrescar();
      await escribir('#oferta-valor', '10');
      await escribir('#oferta-hasta', '2026-11-30');
      boton('Guardar oferta')!.click();
      await refrescar();

      expect(servicio.ponerOferta).toHaveBeenCalledWith(7, {
        tipo: 'DESCUENTO_PORCENTAJE',
        valor: 10,
        hasta: '2026-11-30',
        version: 3,
      });
      expect(emitidos).toHaveLength(1);
    });

    it('las flechas mueven la elección entre los botones, dando la vuelta', async () => {
      await crear(articulo(), true, true);
      await abrir();

      segmentos()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await refrescar();
      expect(marcado()).toEqual(['Descuento en %']);
      expect(segmentos().map((b) => b.tabIndex)).toEqual([-1, 0, -1]);

      segmentos()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      await refrescar();
      expect(marcado()).toEqual(['Descuento en $']);
    });

    it('arranca con el tipo de la oferta guardada y «Sacar oferta» sigue sacándola', async () => {
      await crear(articulo({ ...vigente, tipo: 'DESCUENTO_MONTO', valor: 200 }), true, true);
      await abrir();
      servicio.quitarOferta.mockReturnValue(of(articulo()));

      expect(marcado()).toEqual(['Descuento en $']);
      boton('Sacar oferta')!.click();
      await refrescar();

      expect(servicio.quitarOferta).toHaveBeenCalledWith(7);
      expect(emitidos).toEqual([articulo()]);
    });

    it('guardar sin valor explica el motivo, como hoy', async () => {
      await crear(articulo(), true, true);
      await abrir();

      boton('Guardar oferta')!.click();
      await refrescar();

      expect(servicio.ponerOferta).not.toHaveBeenCalled();
      expect(texto('.motivo-accion')).toBe('Completá el porcentaje o el precio de oferta.');
    });

    it('un error del servidor abre la tarjeta aunque se haya plegado mientras se guardaba', async () => {
      await crear(articulo(), true, true);
      await abrir();
      const pedido = new Subject<Articulo>();
      servicio.ponerOferta.mockReturnValue(pedido);
      await escribir('#oferta-valor', '800');
      boton('Guardar oferta')!.click();
      await refrescar();
      await abrir();
      expect(cabecera().getAttribute('aria-expanded')).toBe('false');

      pedido.error(new HttpErrorResponse({ status: 400, error: { message: 'Datos inválidos', errors: { valor: 'El valor es inválido.' } } }));
      await refrescar();

      expect(cabecera().getAttribute('aria-expanded')).toBe('true');
      expect(texto('#oferta-valor-error')).toBe('El valor es inválido.');
    });

    it('el Vendedor ve el resumen en la tarjeta, sin formulario ni botones de tipo', async () => {
      await crear(articulo(vigente), false, true);
      await abrir();

      expect(texto('.resumen-oferta')).toContain('En oferta a');
      expect(el().querySelector('form, .tipo-segmentado')).toBeNull();
    });
  });

  it('en el escritorio no hay nada de esto: el select de siempre y la sección abierta', async () => {
    await crear(articulo(vigente));

    expect(el().querySelector('.oferta-celular, .seccion-cabecera, .tipo-segmentado, .campo-tipo')).toBeNull();
    expect(el().querySelector('select#oferta-tipo')).not.toBeNull();
    expect(texto('h2')).toBe('Oferta');
    expect(el().querySelector('h2 button')).toBeNull();
    expect(boton('Guardar oferta')).toBeDefined();
    expect(Array.from(el().querySelectorAll<HTMLOptionElement>('#oferta-tipo option')).map((o) => o.textContent!.trim())).toEqual([
      'Precio fijo',
      'Descuento en %',
      'Descuento en $',
    ]);
  });
});
