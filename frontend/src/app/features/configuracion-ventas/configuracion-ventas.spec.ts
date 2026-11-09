import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { PaginaConfiguracionVentas } from './configuracion-ventas';
import { ConfiguracionVentasService } from './configuracion-ventas.service';

describe('PaginaConfiguracionVentas', () => {
  let fixture: ComponentFixture<PaginaConfiguracionVentas>;
  let obtener: ReturnType<typeof vi.fn>;
  let guardar: ReturnType<typeof vi.fn>;

  async function crear(dueno = true, carga = vi.fn(() => of({ topeConsumidorFinal: 10000000 }))) {
    obtener = carga;
    guardar = vi.fn((nueva: { topeConsumidorFinal: number }) => of(nueva));
    await TestBed.configureTestingModule({
      imports: [PaginaConfiguracionVentas],
      providers: [
        { provide: ConfiguracionVentasService, useValue: { obtener, guardar } },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PaginaConfiguracionVentas);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const input = () => el().querySelector<HTMLInputElement>('input[name="topeConsumidorFinal"]')!;

  async function escribirYGuardar(valor: string) {
    input().value = valor;
    input().dispatchEvent(new Event('input'));
    el().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await refrescar();
  }

  it('el título va en la barra y muestra el tope guardado con la referencia de ARCA', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Consumidor Final');
    expect(input().value).toBe('10.000.000,00');
    expect(texto('#tope-pista')).toContain('$ 10.000.000');
    expect(texto('#tope-pista')).toContain('RG 5700/2025');
  });

  it('guardar manda el tope nuevo y lo confirma', async () => {
    await crear();

    await escribirYGuardar('250000,5');

    expect(guardar).toHaveBeenCalledWith({ topeConsumidorFinal: 250000.5 });
    expect(texto('.alerta.exito')).toBe('Se guardó el tope.');
  });

  it('el tope lleva la máscara de importes: 50000 se manda 50000 y, guardado, se ve 50.000,00', async () => {
    await crear();

    await escribirYGuardar('50000');

    expect(input().value).toBe('50.000,00');
    expect(guardar).toHaveBeenCalledWith({ topeConsumidorFinal: 50000 });
  });

  it('un tope de 0 o vacío no se manda y lo explica', async () => {
    await crear();

    await escribirYGuardar('0');
    expect(texto('#tope-error')).toBe('El tope tiene que ser mayor que $ 0.');

    await escribirYGuardar('');
    expect(texto('#tope-error')).toBe('El tope es obligatorio.');
    expect(guardar).not.toHaveBeenCalled();
  });

  it('un tope negativo no se guarda: lo explica debajo del campo y lo enfoca (bugs-relevamiento-celular, S5 y S12)', async () => {
    await crear();

    await escribirYGuardar('-5');

    expect(input().value).toBe('-5');
    expect(texto('#tope-error')).toBe('El tope tiene que ser mayor que $ 0.');
    expect(document.activeElement).toBe(input());
    expect(guardar).not.toHaveBeenCalled();

    await escribirYGuardar('5');

    expect(guardar).toHaveBeenCalledWith({ topeConsumidorFinal: 5 });
  });

  it('si el backend rechaza el tope muestra su mensaje', async () => {
    await crear();
    guardar.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Datos inválidos', errors: { topeConsumidorFinal: 'El tope admite hasta 2 decimales.' } },
          }),
      ),
    );

    await escribirYGuardar('100,5');

    expect(texto('.errores-campos li')).toBe('El tope admite hasta 2 decimales.');
  });

  it('si no carga lo avisa y Reintentar vuelve a pedir', async () => {
    const carga = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
      .mockReturnValue(of({ topeConsumidorFinal: 5000 }));
    await crear(true, carga);

    expect(texto('[role="alert"] span')).toBe('No se pudo cargar la configuración.');
    el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
    await refrescar();

    expect(input().value).toBe('5.000,00');
  });

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Solo el Dueño puede configurar el tope de Consumidor Final.');
    expect(obtener).not.toHaveBeenCalled();
  });
});
