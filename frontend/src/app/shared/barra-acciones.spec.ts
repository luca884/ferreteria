import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BarraAcciones } from './barra-acciones';

@Component({
  imports: [BarraAcciones],
  template: `
    <app-barra-acciones>
      <h1>Rubros</h1>
      <button type="button">Nuevo</button>
    </app-barra-acciones>
  `,
})
class Pantalla {}

describe('BarraAcciones', () => {
  it('muestra el título y las acciones que pone la pantalla', async () => {
    await TestBed.configureTestingModule({ imports: [Pantalla] }).compileComponents();
    const fixture = TestBed.createComponent(Pantalla);
    fixture.detectChanges();

    const barra = (fixture.nativeElement as HTMLElement).querySelector('app-barra-acciones')!;
    expect(barra.querySelector('h1')?.textContent?.trim()).toBe('Rubros');
    expect(barra.querySelector('button')?.textContent?.trim()).toBe('Nuevo');
  });
});
