import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Rubro } from './rubro.models';
import { Rubros } from './rubros';
import { RubrosService } from './rubros.service';

describe('Rubros', () => {
  const buloneria: Rubro = { id: 1, nombre: 'Bulonería', margenPorcentaje: 35 };
  const pinturas: Rubro = { id: 2, nombre: 'Pinturas', margenPorcentaje: 40 };

  let fixture: ComponentFixture<Rubros>;

  async function crear(lista: Rubro[], dueno = false) {
    await TestBed.configureTestingModule({
      imports: [Rubros],
      providers: [
        { provide: RubrosService, useValue: { listar: vi.fn(() => of(lista)), crear: vi.fn() } },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Rubros);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  it('lista los rubros con su margen', async () => {
    await crear([buloneria, pinturas]);

    const filas = Array.from(el().querySelectorAll('tbody tr')).map((f) => f.textContent ?? '');
    expect(filas).toHaveLength(2);
    expect(filas[0]).toContain('Bulonería');
    expect(filas[0]).toContain('35 %');
  });

  it('la barra de estado cuenta los rubros', async () => {
    await crear([buloneria, pinturas]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('2 rubros');

    TestBed.resetTestingModule();
    await crear([buloneria]);
    expect(el().querySelector('.barra-estado')?.textContent?.trim()).toBe('1 rubro');
  });

  it('el Vendedor no ve el alta', async () => {
    await crear([buloneria]);

    expect(el().querySelector('form')).toBeNull();
  });
});
