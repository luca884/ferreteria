import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FormaPago } from '../formas-pago/forma-pago.models';
import { ConfirmacionCobro, DialogoCobro } from './dialogo-cobro';

const NBSP = ' ';
const pesos = (s: string) => '$' + NBSP + s;
const forma = (id: number, nombre: string, medio: FormaPago['medio'], recargoPorcentaje = 0, descuentoPorcentaje = 0): FormaPago => ({
  id, nombre, medio, recargoPorcentaje, descuentoPorcentaje, activa: true, orden: id,
});

/** Regresiones del verificador de descuento en efectivo: el diálogo de cobro con el descuento de la forma. */
describe('DialogoCobro con descuento de la forma (regresiones del verificador)', () => {
  let fixture: ComponentFixture<DialogoCobro>;
  let confirmadas: ConfirmacionCobro[];
  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (s: string | null | undefined) => (s == null ? null : s.replace(/[ \t\r\n]+/g, ' ').trim());
  const descuento = () => limpio(el().querySelector('.descuento-forma')?.textContent);
  const recargo = () => limpio(el().querySelector('.recargo')?.textContent);
  const total = () => el().querySelector('.total')!.textContent!.trim();
  const medio = (nombre: string) =>
    Array.from(el().querySelectorAll('button[role="radio"]')).find((b) => b.textContent!.includes(nombre)) as HTMLButtonElement;
  const monto = (id: number) => el().querySelector<HTMLInputElement>('input[data-forma="' + id + '"]')!;
  async function estabilizar() { fixture.detectChanges(); await fixture.whenStable(); }
  function escribirMonto(id: number, texto: string) {
    monto(id).value = texto;
    monto(id).dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }
  async function abrir(totalCentavos: number, formas: FormaPago[]) {
    await TestBed.configureTestingModule({ imports: [DialogoCobro] }).compileComponents();
    fixture = TestBed.createComponent(DialogoCobro);
    confirmadas = [];
    fixture.componentRef.setInput('totalCentavos', totalCentavos);
    fixture.componentRef.setInput('formas', formas);
    fixture.componentInstance.confirmar.subscribe((c) => confirmadas.push(c));
    await estabilizar();
  }
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const formas = [forma(1, 'Contado', 'EFECTIVO', 0, 10), forma(2, 'Débito', 'DEBITO', 0, 5.5), forma(7, 'Visa', 'CREDITO', 10)];

  it('mixto neto cero: ni recargo ni descuento y total igual al ticket', async () => {
    await abrir(20000, formas);
    medio('Mixto').click();
    await estabilizar();
    escribirMonto(1, '100');
    escribirMonto(7, '100');
    expect(descuento()).toBeNull();
    expect(recargo()).toBeNull();
    expect(total()).toBe(pesos('200,00'));
  });

  it('mixto neto negativo con dos formas con descuento: suma de cada parte redondeada por parte', async () => {
    await abrir(10000, formas);
    medio('Mixto').click();
    await estabilizar();
    escribirMonto(1, '33,35');
    escribirMonto(2, '66,65');
    expect(descuento()).toBe('Descuento por forma de pago −' + pesos('7,01'));
    expect(total()).toBe(pesos('92,99'));
  });

  it('debito con 5,5 %: marca, porcentaje en la linea y emite el negativo', async () => {
    await abrir(10000, formas);
    medio('Débito').click();
    await estabilizar();
    expect(limpio(medio('Débito').textContent)).toBe('Débito −5,5' + NBSP + '%');
    expect(descuento()).toBe('Descuento por forma de pago (5,5 %) −' + pesos('5,50'));
    expect(total()).toBe(pesos('94,50'));
  });
});
