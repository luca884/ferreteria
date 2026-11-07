import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { CampoMoneda } from './campo-moneda';
import { ImporteInput } from './importe-input';

@Component({
  imports: [CampoMoneda, ImporteInput, ReactiveFormsModule],
  template: `
    <app-campo-moneda id="pesos"><input id="costo" appImporte [formControl]="costo" /></app-campo-moneda>
    <app-campo-moneda id="dolares" moneda="USD"><input id="en-dolares" appImporte [formControl]="enDolares" /></app-campo-moneda>
    <app-campo-moneda id="suelto-pesos">
      <input id="suelto" appImporte [value]="texto()" (input)="texto.set($any($event.target).value)" />
    </app-campo-moneda>
    <app-campo-moneda id="sin-moneda" [moneda]="moneda()"><input id="valor" appImporte [formControl]="valor" /></app-campo-moneda>
    <input id="porcentaje" appImporte [formControl]="porcentaje" />
  `,
})
class Anfitrion {
  readonly costo = new FormControl<number | null>(578378);
  readonly enDolares = new FormControl<number | null>(12);
  readonly texto = signal('');
  readonly moneda = signal<'ARS' | 'USD' | null>(null);
  readonly valor = new FormControl<number | null>(10);
  readonly porcentaje = new FormControl<number | null>(5);
}

describe('CampoMoneda (arreglos-compras, S6)', () => {
  let fixture: ComponentFixture<Anfitrion>;
  const raiz = () => fixture.nativeElement as HTMLElement;
  const campo = (id: string) => raiz().querySelector<HTMLInputElement>(`#${id}`)!;
  const signo = (id: string) => raiz().querySelector(`#${id} .signo`);

  function teclear(input: HTMLInputElement, texto: string): void {
    input.value = texto;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  beforeEach(async () => {
    fixture = TestBed.createComponent(Anfitrion);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('muestra el signo de la moneda dentro del recuadro, fijo y oculto para el lector de pantalla', () => {
    expect(signo('pesos')?.textContent?.trim()).toBe('$');
    expect(signo('dolares')?.textContent?.trim()).toBe('US$');
    expect(signo('pesos')?.getAttribute('aria-hidden')).toBe('true');
    expect(campo('costo').closest('app-campo-moneda')?.id).toBe('pesos');
  });

  it('un importe cargado se ve con dos decimales', () => {
    expect(campo('costo').value).toBe('578.378,00');
    expect(campo('en-dolares').value).toBe('12,00');
  });

  it('al salir del campo completa los decimales, sin cambiar el valor del control', () => {
    teclear(campo('costo'), '9500');
    expect(campo('costo').value).toBe('9.500');
    const cambios: (number | null)[] = [];
    fixture.componentInstance.costo.valueChanges.subscribe((v) => cambios.push(v));

    campo('costo').dispatchEvent(new Event('blur'));

    expect(campo('costo').value).toBe('9.500,00');
    expect(fixture.componentInstance.costo.value).toBe(9500);
    expect(cambios).toEqual([]);
  });

  it('suelto (con [value]), también completa los decimales al salir; vacío queda vacío', () => {
    teclear(campo('suelto'), '1500,5');
    campo('suelto').dispatchEvent(new Event('blur'));
    expect(campo('suelto').value).toBe('1.500,50');

    teclear(campo('suelto'), '');
    campo('suelto').dispatchEvent(new Event('blur'));
    expect(campo('suelto').value).toBe('');
  });

  it('sin moneda (un valor que a veces es porcentaje), no hay signo ni decimales de más', () => {
    expect(signo('sin-moneda')).toBeNull();
    expect(campo('valor').value).toBe('10');

    fixture.componentInstance.moneda.set('ARS');
    fixture.detectChanges();

    expect(signo('sin-moneda')?.textContent?.trim()).toBe('$');
  });

  it('un campo de porcentaje, sin el envoltorio, sigue como siempre', () => {
    expect(campo('porcentaje').value).toBe('5');
    teclear(campo('porcentaje'), '4,4');
    campo('porcentaje').dispatchEvent(new Event('blur'));
    expect(campo('porcentaje').value).toBe('4,4');
  });
});
