import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
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

  async function crear(a: Articulo, editable = true) {
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

  it('sin valor no se puede guardar', async () => {
    await crear(articulo());

    expect(boton('Guardar oferta')!.disabled).toBe(true);
    expect(boton('Sacar oferta')).toBeUndefined();
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
});
