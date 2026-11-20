import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { ArticulosService } from '../articulos/articulos.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { GENERAR_CLAVE } from '../mostrador/ticket.store';
import { BorradorDeFactura, CompraRegistrada, PlanCompra } from './compra.models';
import { ComprasService } from './compras.service';
import { NuevaCompra } from './nueva-compra';

registerLocaleData(localeEsAr);

describe('Nueva compra en el celular (T16)', () => {
  let fixture: ComponentFixture<NuevaCompra>;
  const vistaPrevia = vi.fn();
  const leerFactura = vi.fn();
  const registrar = vi.fn();
  const pedido = {
    proveedorId: 4, tipo: 'FA' as const, puntoVenta: 1, numero: 4512, fecha: '2026-11-15',
    renglones: [{ articuloId: 1, cantidad: 2, costoUnitario: 50 }], recargos: [{ nombre: 'Embalaje', porcentaje: 2 }],
  };
  const renglon: PlanCompra['renglones'][number] = {
    articuloId: 1, codigoInterno: 'TOR-1', descripcion: 'Tornillo', unidad: 'KILO', cantidad: 2,
    costoAnterior: 25, costoNuevo: 50, costoRealAnterior: 26, costoRealNuevo: 51,
    precioAnterior: 35, precioNuevo: 65, stockAnterior: 10.5, stockNuevo: 12.5,
    subtotal: 100, advertencia: 'El costo cambia más de 50 %: revisalo.', alicuotaIva: 21,
  };
  const filasPrevia = () => [...q('.vista-previa')!.querySelectorAll<HTMLButtonElement>('.acc-b')];
  const confirmar = () => q<HTMLButtonElement>('.vista-previa button.primario')!;
  const totales = () => [...q('.vista-previa')!.querySelectorAll('.totales p')].map((fila) =>
    [...fila.children].map((celda) => celda.textContent?.trim()),
  );
  const listar = vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', activo: true }]));
  const q = <T extends HTMLElement>(selector: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  const secciones = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.compra-paso-titulo button')];
  const expansion = () => secciones().map((boton) => boton.getAttribute('aria-expanded'));
  const ver = () =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Ver vista previa')!;

  function simularPantalla(celular: boolean) {
    vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: celular && consulta.includes('639.98px'), media: consulta }));
  }

  async function crear(celular = true) {
    if (celular) simularPantalla(true);
    vistaPrevia.mockReset();
    leerFactura.mockReset();
    registrar.mockReset();
    listar.mockClear();
    await TestBed.configureTestingModule({
      imports: [NuevaCompra],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { esDueno: () => true } },
        { provide: ArticulosService, useValue: {} },
        { provide: ComprasService, useValue: { vistaPrevia, leerFactura, registrar } },
        { provide: GENERAR_CLAVE, useValue: vi.fn().mockReturnValueOnce('clave-1').mockReturnValue('clave-2') },
        { provide: ProveedoresService, useValue: { listar } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(NuevaCompra);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function tocar(boton: HTMLButtonElement) {
    boton.click();
    await refrescar();
  }

  async function cargarPorPdf(renglones?: BorradorDeFactura['renglones']) {
    const borrador: BorradorDeFactura = {
      proveedorId: 4, proveedorNombre: 'Bulonera Sur', cuits: [], tipo: 'FA', puntoVenta: 1, numero: 4512,
      fecha: '2026-11-15', recargos: [{ nombre: 'Embalaje', porcentaje: 2, importe: 2 }],
      renglones: renglones ?? [{
        codigo: 'TOR-1', descripcion: 'Tornillo', cantidad: 2, costoUnitario: 50, neto: 100, articuloId: 1,
        articuloDescripcion: 'Tornillo', articuloCodigoInterno: 'TOR-1', articuloMoneda: 'ARS', cantidadNoEntera: false,
      }],
      sumaNetos: 100, control: 'CUADRA', cantidadNoEntera: false,
    };
    leerFactura.mockReturnValue(of(borrador));
    const entrada = q<HTMLInputElement>('app-selector-archivo input[type="file"]')!;
    const archivo = new File(['factura'], 'factura.pdf', { type: 'application/pdf' });
    const abrir = vi.spyOn(entrada, 'click').mockImplementation(() => {
      Object.defineProperty(entrada, 'files', { value: [archivo] });
      entrada.dispatchEvent(new Event('change'));
    });
    await tocar(q<HTMLButtonElement>('app-selector-archivo .elegir-archivo')!);
    expect(abrir).toHaveBeenCalledOnce();
    expect(leerFactura).toHaveBeenCalledExactlyOnceWith(archivo);
  }

  async function abrirPrevia(conRecargos = true) {
    await cargarPorPdf();
    if (!conRecargos) {
      await tocar(secciones()[2]);
      await tocar(q<HTMLButtonElement>('button[aria-label="Quitar el recargo 1"]')!);
    }
    const plan: PlanCompra = {
      proveedorNombre: 'Bulonera Sur', moneda: 'ARS', suben: 1, bajan: 1, iguales: 0,
      renglones: [renglon, { ...renglon, articuloId: 2, codigoInterno: 'TUE-2', descripcion: 'Tuerca', advertencia: null }],
      recargos: conRecargos ? pedido.recargos : [], neto: 200, recargosImporte: conRecargos ? 4 : 0,
      subtotalSinIva: conRecargos ? 204 : 200,
      iva: [{ porcentaje: 10.5, base: 100, importe: 10.5 }, { porcentaje: 21, base: 100, importe: 21 }],
      total: conRecargos ? 235.5 : 231.5,
    };
    vistaPrevia.mockReturnValue(of(plan));
    await tocar(ver());
  }

  afterEach(() => vi.unstubAllGlobals());

  it('muestra el comprobante, los contadores y cada renglón cerrado, con cantidad y subtotal', async () => {
    await crear();
    await abrirPrevia();
    expect(q('.vista-previa .datos')?.textContent).toContain('Bulonera Sur · Factura A 00001-00004512');
    expect(q('.vista-previa .datos')?.textContent).toContain('1 sube · 1 baja · 0 iguales');
    expect(filasPrevia().map((b) => b.getAttribute('aria-expanded'))).toEqual(['false', 'false']);
    for (const [i, boton] of filasPrevia().entries()) {
      expect(boton.textContent).toContain(i === 0 ? 'Tornillo' : 'Tuerca');
      expect(boton.textContent).toContain('2 kilos');
      expect(boton.textContent).toContain('$\u00a0100,00');
      expect(q(`#${boton.getAttribute('aria-controls')}`)?.hidden).toBe(true);
    }
  });

  it('permite abrir dos renglones juntos y cerrar uno sin cerrar el otro', async () => {
    await crear();
    await abrirPrevia();
    const [primero, segundo] = filasPrevia();
    await tocar(primero);
    await tocar(segundo);
    expect(filasPrevia().map((b) => b.getAttribute('aria-expanded'))).toEqual(['true', 'true']);
    await tocar(primero);
    expect(filasPrevia().map((b) => b.getAttribute('aria-expanded'))).toEqual(['false', 'true']);
  });

  it.each([true, false])('muestra los valores anteriores y nuevos del renglón (recargos: %s)', async (conRecargos) => {
    await crear();
    await abrirPrevia(conRecargos);
    const [primero, segundo] = filasPrevia();
    await tocar(primero);
    const detalle = q(`#${primero.getAttribute('aria-controls')}`)!;
    expect(detalle.hidden).toBe(false);
    expect([...detalle.querySelectorAll('dt, dd')].map((e) => e.textContent?.trim())).toEqual([
      'Costo antes', '$\u00a025,00 / kg', 'Costo nuevo', '$\u00a050,00 / kg',
      ...(conRecargos ? ['Costo real antes', '$\u00a026,00 / kg', 'Costo real nuevo', '$\u00a051,00 / kg'] : []),
      'Precio antes', '$\u00a035,00 / kg', 'Precio nuevo', '$\u00a065,00 / kg',
      'Stock antes', '10,5', 'Stock después', '12,5 kilos',
    ]);
    expect(detalle.querySelector('.aviso-fila')?.textContent).toBe(renglon.advertencia);
    expect(q(`#${segundo.getAttribute('aria-controls')} .aviso-fila`)).toBeNull();
  });

  it.each([true, false])('muestra los mismos totales y el mismo orden que en escritorio (recargos: %s)', async (conRecargos) => {
    await crear();
    await abrirPrevia(conRecargos);
    expect(filasPrevia()).toHaveLength(2);
    const celular = totales();
    simularPantalla(false);
    window.dispatchEvent(new Event('resize'));
    await refrescar();
    expect(q('.vista-previa table')).not.toBeNull();
    expect(celular).toEqual(totales());
    expect(celular.map(([rotulo]) => rotulo)).toEqual([
      'Neto', ...(conRecargos ? ['Recargos'] : []), 'Subtotal sin IVA', 'IVA 10,5 %', 'IVA 21 %', 'Total',
    ]);
  });

  it('confirma con el pedido y la clave una sola vez y se deshabilita hasta terminar', async () => {
    await crear();
    await abrirPrevia();
    const respuesta = new Subject<CompraRegistrada>();
    registrar.mockReturnValue(respuesta);
    const boton = confirmar();
    expect(boton.parentElement?.matches('.ficha-pie.en-pagina')).toBe(true);
    expect(boton.disabled).toBe(false);
    await tocar(boton);
    expect(confirmar().disabled).toBe(true);
    await tocar(confirmar());
    expect(registrar).toHaveBeenCalledExactlyOnceWith({ ...pedido, clave: 'clave-1' });
    respuesta.next({ repetida: false, compra: {
      id: 9, ...pedido, comprobante: 'Factura A 00001-00004512', proveedorNombre: 'Bulonera Sur',
      moneda: 'ARS', neto: 100, registrada: '2026-11-15T12:00:00', renglones: [],
    } });
    respuesta.complete();
    await refrescar();
    expect(q('.vista-previa')).toBeNull();
    expect(q('.exito[role="status"]')?.textContent).toContain('Compra registrada: Factura A 00001-00004512 de Bulonera Sur.');
    expect(document.activeElement).toBe(q('.exito[role="status"]'));
  });

  it('«Volver» descarta la vista previa sin perder los datos ni registrar', async () => {
    await crear();
    await abrirPrevia();
    const numero = q<HTMLInputElement>('input[name="numero"]')!;
    const volver = q<HTMLButtonElement>('.vista-previa .ficha-pie button.secundario')!;
    expect(volver.textContent?.trim()).toBe('Volver');
    expect(document.activeElement).toBe(q('.vista-previa h2'));
    await tocar(volver);
    expect(q('.vista-previa')).toBeNull();
    expect(document.activeElement).toBe(secciones()[0]);
    expect(ver().disabled).toBe(false);
    expect(q('input[name="numero"]')).toBe(numero);
    expect(numero.value).toBe('4512');
    expect(numero.disabled).toBe(false);
    expect(vistaPrevia).toHaveBeenCalledTimes(1);
    expect(registrar).not.toHaveBeenCalled();
  });

  it('editar el número descarta la vista previa móvil y exige revisar un nuevo pedido', async () => {
    await crear();
    await abrirPrevia();
    expect(filasPrevia()).toHaveLength(2);
    const numero = q<HTMLInputElement>('input[name="numero"]')!;
    numero.value = '4513';
    numero.dispatchEvent(new Event('input'));
    await refrescar();
    expect(q('.vista-previa')).toBeNull();
    expect(registrar).not.toHaveBeenCalled();
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenLastCalledWith({ ...pedido, numero: 4513 });
    expect(filasPrevia().every((b) => b.getAttribute('aria-expanded') === 'false')).toBe(true);
  });

  it('un rechazo de vista previa deja el formulario editable y permite pedirla de nuevo', async () => {
    await crear();
    await cargarPorPdf();
    vistaPrevia.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
    await tocar(ver());
    expect(q('.vista-previa')).toBeNull();
    expect(q('[role="alert"]')?.textContent).toContain('No se pudo armar la vista previa. Probá de nuevo.');
    expect(ver().disabled).toBe(false);
    const numero = q<HTMLInputElement>('input[name="numero"]')!;
    expect(numero.disabled).toBe(false);
    numero.value = '4513';
    numero.dispatchEvent(new Event('input'));
    vistaPrevia.mockReturnValueOnce(new Subject<PlanCompra>());
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenLastCalledWith({ ...pedido, numero: 4513 });
  });

  it.each([true, false])('conserva el mensaje y la clave al reintentar un 409 (celular: %s)', async (celular) => {
    await crear(celular);
    await abrirPrevia();
    const mensaje = 'Esa compra ya está cargada: Factura A 00001-00004512 de Bulonera Sur.';
    registrar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { message: mensaje } })));
    await tocar(confirmar());
    expect(q('[role="alert"]')?.textContent).toContain(mensaje);
    if (celular) expect(document.activeElement).toBe(q('[role="alert"]'));
    else expect(q('[role="alert"]')?.hasAttribute('tabindex')).toBe(false);
    expect(confirmar().disabled).toBe(false);
    await tocar(confirmar());
    expect(registrar.mock.calls).toEqual([[{ ...pedido, clave: 'clave-1' }], [{ ...pedido, clave: 'clave-1' }]]);
    expect(q('app-nueva-compra > .exito[role="status"]')?.textContent).toBeUndefined();
  });

  it('mantiene en escritorio la tabla con recargos, los datos y la confirmación en línea', async () => {
    await crear(false);
    await abrirPrevia();
    expect(q('.acc-b, .ficha-pie')).toBeNull();
    expect(q('.vista-previa .datos')?.textContent?.trim().replace(/\s+/g, ' ')).toBe('Bulonera Sur · 1 sube · 1 baja · 0 iguales');
    expect([...q('.vista-previa table')!.querySelectorAll('th')].map((e) => e.textContent?.trim())).toEqual([
      'Artículo', 'Cantidad', 'Costo antes', 'Costo nuevo', 'Costo real antes', 'Costo real nuevo',
      'Precio antes', 'Precio nuevo', 'Stock antes → después', 'Subtotal',
    ]);
    expect(q('.vista-previa tbody tr.advertencia .aviso-fila')?.textContent).toBe(renglon.advertencia);
    expect(confirmar().parentElement?.className).toBe('acciones');
  });

  it('abre sólo Comprobante al entrar y pliega cada tarjeta sin perder los campos', async () => {
    await crear();
    expect(secciones().map((boton) => boton.firstElementChild?.textContent?.trim())).toEqual([
      'Comprobante', 'Artículos', 'Recargos del costo',
    ]);
    expect(expansion()).toEqual(['true', 'false', 'false']);
    expect(q('.compra-paso-titulo .compra-paso-resumen')?.textContent?.trim()).toBe('0');
    expect(q('.recargos-costo .compra-paso-resumen')?.textContent?.trim()).toBe('ninguno');
    const numero = q<HTMLInputElement>('input[name="numero"]')!;
    numero.value = '4512';
    numero.dispatchEvent(new Event('input'));
    await tocar(secciones()[0]);
    expect(expansion()).toEqual(['false', 'false', 'false']);
    await tocar(secciones()[1]);
    expect(expansion()).toEqual(['false', 'true', 'false']);
    await tocar(secciones()[2]);
    expect(expansion()).toEqual(['false', 'true', 'true']);
    await tocar(secciones()[0]);
    expect(expansion()).toEqual(['true', 'true', 'true']);
    expect(q('input[name="numero"]')).toBe(numero);
    expect(numero.value).toBe('4512');
  });

  it('el pie sigue validando al tocarlo vacío y pide la misma vista previa con el PDF cargado', async () => {
    await crear();
    expect(ver().parentElement?.matches('.ficha-pie.en-pagina')).toBe(true);
    expect(ver().disabled).toBe(false);
    await tocar(ver());
    expect(q('[role="alert"]')?.textContent).toContain('Elegí el proveedor.');
    expect(vistaPrevia).not.toHaveBeenCalled();

    await cargarPorPdf();
    expect(q('.compra-paso-titulo .compra-paso-resumen')?.textContent?.trim()).toBe('1');
    expect(q('.recargos-costo .compra-paso-resumen')?.textContent?.trim()).toBe('1');
    expect(expansion()).toEqual(['true', 'false', 'false']);

    vistaPrevia.mockReturnValue(new Subject<PlanCompra>());
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenCalledExactlyOnceWith({
      proveedorId: 4, tipo: 'FA', puntoVenta: 1, numero: 4512, fecha: '2026-11-15',
      renglones: [{ articuloId: 1, cantidad: 2, costoUnitario: 50 }], recargos: [{ nombre: 'Embalaje', porcentaje: 2 }],
    });
    expect(ver().disabled).toBe(true);
  });

  it('un error de recargos del servidor abre su sección plegada, porque es el único lugar donde se muestra', async () => {
    await crear();
    await cargarPorPdf();
    expect(expansion()).toEqual(['true', 'false', 'false']);
    vistaPrevia.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { errors: { recargosCosto: 'Un recargo no puede ser 0 %.' } } })),
    );
    await tocar(ver());
    expect(expansion()).toEqual(['true', 'false', 'true']);
    expect(q('.recargos-costo .campo-error')?.textContent).toContain('Un recargo no puede ser 0 %.');
    expect(document.activeElement).toBe(q('.recargos-costo .campo-error'));
  });

  it('en escritorio conserva los títulos, las secciones abiertas y la validación en línea', async () => {
    await crear(false);
    expect(secciones()).toHaveLength(0);
    expect(q('.compra-paso-titulo, .ficha-pie, .compra-paso-resumen')).toBeNull();
    expect([...(fixture.nativeElement as HTMLElement).querySelectorAll('.paso > h2')].map((h) => h.textContent)).toEqual([
      'Comprobante', 'Artículos', 'Recargos del costo',
    ]);
    expect(q('.paso > .encabezado')).not.toBeNull();
    expect(q('.paso > .buscador')).not.toBeNull();
    expect(q('.paso.recargos-costo > button')).not.toBeNull();
    expect(ver().parentElement?.className).toBe('acciones');
    expect(ver().disabled).toBe(false);
    await tocar(ver());
    expect(q('[role="alert"]')?.textContent).toContain('Elegí el proveedor.');
    expect(vistaPrevia).not.toHaveBeenCalled();
  });

  it.each([true, false])('mantiene la tabla y la edición de renglones (celular: %s)', async (celular) => {
    await crear(celular);
    await cargarPorPdf([{
      codigo: 'PROV-1', descripcion: 'Tornillo', cantidad: 2, costoUnitario: 50, neto: 100, articuloId: 1,
      articuloDescripcion: 'Tornillo', articuloCodigoInterno: 'TOR-1', articuloMoneda: 'USD', cantidadNoEntera: true,
    }]);
    if (celular) await tocar(secciones()[1]);
    const tabla = q('table.renglones')!;
    const fila = tabla.querySelector<HTMLTableRowElement>('tbody tr')!;
    expect(tabla.querySelector('caption')?.textContent).toBe('Renglones de la compra');
    expect(tabla.querySelector('thead th[scope="col"]')?.textContent).toBe('Artículo');
    expect(fila.getAttribute('aria-label')).toBe(celular ? 'Tornillo (USD)' : null);
    expect(fila.cells[0].textContent).toContain('TOR-1 · Tornillo (USD)');
    expect(fila.querySelector('.codigo-proveedor')?.textContent).toBe('Cód. proveedor PROV-1');
    expect(fila.querySelector('.aviso-fila')?.textContent).toContain('La cantidad no es entera');
    expect([...fila.querySelectorAll('.compra-renglon-etiqueta')].map((e) => e.textContent)).toEqual(
      celular ? ['Cantidad', 'Costo unitario sin IVA'] : [],
    );
    const cantidad = q<HTMLInputElement>('input[name="cantidad-0"][aria-label="Cantidad de Tornillo"]')!;
    const costo = q<HTMLInputElement>('input[name="costo-0"][aria-label="Costo de Tornillo"]')!;
    for (const [campo, valor] of [[cantidad, '3'], [costo, '55,25']] as const) {
      campo.value = valor;
      campo.dispatchEvent(new Event('input'));
      await refrescar();
    }
    vistaPrevia.mockReturnValue(new Subject<PlanCompra>());
    await tocar(ver());
    expect(vistaPrevia).toHaveBeenCalledExactlyOnceWith({
      proveedorId: 4, tipo: 'FA', puntoVenta: 1, numero: 4512, fecha: '2026-11-15',
      renglones: [{ articuloId: 1, cantidad: 3, costoUnitario: 55.25 }], recargos: [{ nombre: 'Embalaje', porcentaje: 2 }],
    });
    await tocar(fila.querySelector('button')!);
    expect(tabla.querySelector('tbody tr')).toBeNull();
    expect(q('.estado-vacio')?.textContent).toBe('Todavía no agregaste artículos.');
  });

  it('mantiene las tres acciones y el aviso de un renglón sin artículo', async () => {
    await crear();
    await cargarPorPdf([{
      codigo: 'PROV-2', descripcion: 'Tuerca', cantidad: 2.5, costoUnitario: 40, neto: 100, articuloId: null,
      articuloDescripcion: null, articuloCodigoInterno: null, articuloMoneda: null, cantidadNoEntera: true,
    }]);
    await tocar(secciones()[1]);
    const fila = q('ul[aria-label="Renglones sin artículo"] li')!;
    expect(fila.textContent).toContain('Sin artículo: PROV-2 — Tuerca');
    expect(fila.querySelector('.aviso-fila')?.textContent).toContain('La cantidad no es entera');
    const botones = [...fila.querySelectorAll('button')];
    expect(botones.map((b) => b.textContent?.trim())).toEqual(['Elegir artículo', 'Crear artículo', 'Quitar']);
    await tocar(botones[0]);
    expect(fila.classList.contains('resolviendo')).toBe(true);
    expect(botones[2].getAttribute('aria-label')).toBe('Quitar sin artículo PROV-2');
    await tocar(botones[2]);
    expect(q('.sin-articulo')).toBeNull();
  });

  it('mantiene los nombres de los campos de recargo y explica el tope de 5', async () => {
    await crear();
    await tocar(secciones()[2]);
    const agregar = q<HTMLButtonElement>('.recargos-costo > button')!;
    for (let i = 0; i < 5; i++) {
      await tocar(agregar);
      expect(q(`input[name="recargo-nombre-${i}"]`)).not.toBeNull();
      expect(q(`input[name="recargo-porcentaje-${i}"]`)).not.toBeNull();
    }
    expect(agregar.getAttribute('aria-disabled')).toBe('true');
    await tocar(agregar);
    const aviso = q('[role="status"].motivo-accion');
    expect(aviso?.textContent?.trim()).toBe('Ya cargaste el máximo de recargos (5).');
    expect(agregar.previousElementSibling).toBe(aviso);
    expect(q('input[name="recargo-nombre-5"]')).toBeNull();
    await tocar(q<HTMLButtonElement>('button[aria-label="Quitar el recargo 5"]')!);
    expect(agregar.getAttribute('aria-disabled')).not.toBe('true');
    expect(q('input[name="recargo-nombre-4"]')).toBeNull();
  });

  it('al cruzar el corte cambia la presentación sin reiniciar los datos', async () => {
    await crear();
    await tocar(secciones()[0]);
    const fecha = q<HTMLInputElement>('input[name="fecha"]');
    simularPantalla(false);
    window.dispatchEvent(new Event('resize'));
    await refrescar();
    expect(secciones()).toHaveLength(0);
    expect(ver().parentElement?.className).toBe('acciones');
    expect(q('input[name="fecha"]')).toBe(fecha);
    simularPantalla(true);
    window.dispatchEvent(new Event('resize'));
    await refrescar();
    expect(expansion()).toEqual(['false', 'false', 'false']);
    expect(q('input[name="fecha"]')).toBe(fecha);
    expect(listar).toHaveBeenCalledOnce();
  });
});
