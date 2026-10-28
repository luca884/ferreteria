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
    await TestBed.configureTestingModule({
      imports: [PaginaDatosFiscales],
      providers: [
        { provide: DatosFiscalesService, useValue: { obtener, guardar } },
        { provide: AuthService, useValue: { esDueno: signal(dueno), refrescarSesion } },
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

  it('el Vendedor ve el aviso y no pide los datos', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Solo el Dueño puede ver y editar los datos de la ferretería.');
    expect(obtener).not.toHaveBeenCalled();
    expect(el().querySelector('form')).toBeNull();
  });
});
