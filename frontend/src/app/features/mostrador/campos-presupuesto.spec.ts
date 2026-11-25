import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CamposPresupuesto } from './campos-presupuesto';

describe('CamposPresupuesto (mostrador-simple S10)', () => {
  let fixture: ComponentFixture<CamposPresupuesto>;
  const el = () => fixture.nativeElement as HTMLElement;
  const campo = (nombre: string) => el().querySelector<HTMLInputElement>(`input[name="${nombre}"]`);

  function teclear(destino: HTMLElement, key: string, hora = 0) {
    vi.spyOn(Date, 'now').mockReturnValue(hora);
    const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    destino.dispatchEvent(evento);
    return evento;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CamposPresupuesto] }).compileComponents();
    fixture = TestBed.createComponent(CamposPresupuesto);
    fixture.detectChanges();
  });
  afterEach(() => vi.restoreAllMocks());

  it('arranca con 15 días, sin observaciones y con el campo de cliente', () => {
    expect(campo('validez')!.value).toBe('15');
    expect(campo('observaciones')!.value).toBe('');
    expect(campo('cliente')).not.toBeNull();
  });

  it('con un cliente elegido no hay campo de cliente de texto libre', () => {
    fixture.componentRef.setInput('clienteElegido', 'Obra Perez');
    fixture.detectChanges();

    expect(campo('cliente')).toBeNull();
    expect(campo('validez')).not.toBeNull();
  });

  it('lo que se escribe pasa al valor del campo y avisa que se editó', () => {
    const editado = vi.fn();
    fixture.componentInstance.editado.subscribe(editado);

    const observaciones = campo('observaciones')!;
    observaciones.value = 'Entrega el viernes';
    observaciones.dispatchEvent(new Event('input'));

    expect(fixture.componentInstance.observaciones()).toBe('Entrega el viernes');
    expect(editado).toHaveBeenCalledTimes(1);
  });

  it('Enter guarda y Esc devuelve el foco al buscador, sin que la tecla llegue más arriba', () => {
    const guardar = vi.fn();
    const salir = vi.fn();
    fixture.componentInstance.guardar.subscribe(guardar);
    fixture.componentInstance.salir.subscribe(salir);

    const enter = teclear(campo('validez')!, 'Enter');
    const esc = teclear(campo('validez')!, 'Escape');

    expect(guardar).toHaveBeenCalledTimes(1);
    expect(enter.defaultPrevented).toBe(true);
    expect(salir).toHaveBeenCalledTimes(1);
    expect(esc.defaultPrevented).toBe(true);
  });

  it('un escaneo que termina en Enter no guarda y devuelve el campo a su valor inicial', () => {
    const guardar = vi.fn();
    const descartado = vi.fn();
    fixture.componentInstance.guardar.subscribe(guardar);
    fixture.componentInstance.escaneoDescartado.subscribe(descartado);

    const observaciones = campo('observaciones')!;
    let hora = 0;
    for (const letra of '7791234567890') teclear(observaciones, letra, (hora += 5));
    observaciones.value = '7791234567890';
    observaciones.dispatchEvent(new Event('input'));
    teclear(observaciones, 'Enter', (hora += 5));

    expect(guardar).not.toHaveBeenCalled();
    expect(descartado).toHaveBeenCalledTimes(1);
    expect(observaciones.value).toBe('');
    expect(fixture.componentInstance.observaciones()).toBe('');
  });

  it('muestra el error de la validez con aria-invalid y el aviso del contenedor', () => {
    fixture.componentRef.setInput('error', 'La validez va de 1 a 90 días.');
    fixture.componentRef.setInput('aviso', 'El servidor está ocupado.');
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')?.textContent?.trim()).toBe('La validez va de 1 a 90 días.');
    expect(el().querySelector('[role="status"]')?.textContent?.trim()).toBe('El servidor está ocupado.');
    expect(campo('validez')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('con un envío en curso los campos no se tocan', () => {
    fixture.componentRef.setInput('bloqueado', true);
    fixture.detectChanges();

    expect(campo('validez')!.disabled).toBe(true);
    expect(campo('observaciones')!.disabled).toBe(true);
  });
});
