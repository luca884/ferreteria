import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SelectorArchivo } from './selector-archivo';

@Component({
  imports: [SelectorArchivo],
  template: `
    <app-selector-archivo id="lista" [archivo]="lista()" (elegido)="lista.set($event)" />
    <app-selector-archivo
      id="factura"
      rotulo="Factura en PDF"
      acepta=".pdf,application/pdf"
      ayuda="La factura del proveedor en PDF."
      [archivo]="factura()"
      [deshabilitado]="ocupado()"
      (elegido)="elegidos.push($event)"
    />
  `,
})
class Anfitrion {
  readonly lista = signal<File | null>(null);
  readonly factura = signal<File | null>(null);
  readonly ocupado = signal(false);
  readonly elegidos: (File | null)[] = [];
}

describe('SelectorArchivo (arreglos-compras, S3)', () => {
  let fixture: ComponentFixture<Anfitrion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Anfitrion] }).compileComponents();
    fixture = TestBed.createComponent(Anfitrion);
    fixture.detectChanges();
  });

  const selector = (id: string) => fixture.nativeElement.querySelector(`#${id}`) as HTMLElement;
  const texto = (id: string, clase: string) =>
    selector(id).querySelector(clase)?.textContent?.replace(/\s+/g, ' ').trim();
  const entrada = (id: string) =>
    selector(id).querySelector('input[type=file]') as HTMLInputElement;

  function elegirArchivo(id: string, nombre: string) {
    const input = entrada(id);
    Object.defineProperty(input, 'files', { value: [new File(['x'], nombre)], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('sin datos propios, es el de la lista de precios: rótulo, archivos aceptados y ayuda de siempre', () => {
    expect(texto('lista', '.rotulo')).toBe('Archivo de la lista');
    expect(entrada('lista').accept).toBe('.xlsx,.xls,.csv,.pdf');
    expect(texto('lista', '.ayuda')).toBe(
      'Excel (.xlsx, .xls), CSV o PDF, hasta 25 MB. También podés arrastrarlo hasta acá.',
    );
    expect(texto('lista', '.nombre-archivo')).toBe('Ningún archivo elegido');
  });

  it('con datos propios, muestra su rótulo, acepta lo que dice y muestra su ayuda', () => {
    expect(texto('factura', '.rotulo')).toBe('Factura en PDF');
    expect(entrada('factura').accept).toBe('.pdf,application/pdf');
    expect(texto('factura', '.ayuda')).toBe('La factura del proveedor en PDF.');
    expect(texto('factura', 'button')).toBe('Elegir archivo…');
  });

  it('dos selectores en la misma pantalla no comparten el rótulo', () => {
    const grupos = [...fixture.nativeElement.querySelectorAll('[role=group]')] as HTMLElement[];
    const rotulos = grupos.map((g) =>
      document.getElementById(g.getAttribute('aria-labelledby')!)?.textContent?.trim(),
    );

    expect(rotulos).toEqual(['Archivo de la lista', 'Factura en PDF']);
  });

  it('avisa el archivo elegido y muestra el nombre que le pasa el padre', () => {
    elegirArchivo('lista', 'lista-bulonera.xlsx');

    expect(fixture.componentInstance.lista()?.name).toBe('lista-bulonera.xlsx');
    expect(texto('lista', '.nombre-archivo')).toBe('lista-bulonera.xlsx');
  });

  it('deshabilitado, el botón no se puede usar', () => {
    fixture.componentInstance.ocupado.set(true);
    fixture.detectChanges();

    expect((selector('factura').querySelector('button') as HTMLButtonElement).disabled).toBe(true);
    expect(entrada('factura').disabled).toBe(true);
  });
});

@Component({
  imports: [SelectorArchivo],
  template: `
    <app-selector-archivo
      id="larga"
      ayuda="Importá el Excel del proveedor. También podés arrastrar la lista completa hasta acá."
    />
    <app-selector-archivo id="sin-pista" ayuda="El remito del proveedor en PDF." />
    <app-selector-archivo id="por-defecto" />
  `,
})
class AnfitrionPista {}

describe('SelectorArchivo · la pista de arrastre se separa sin depender de la redacción (S10)', () => {
  let fixture: ComponentFixture<AnfitrionPista>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AnfitrionPista] }).compileComponents();
    fixture = TestBed.createComponent(AnfitrionPista);
    fixture.detectChanges();
  });

  const selector = (id: string) => fixture.nativeElement.querySelector(`#${id}`) as HTMLElement;
  const texto = (id: string, clase: string) =>
    selector(id).querySelector(clase)?.textContent?.replace(/\s+/g, ' ').trim();

  it('una pista de varias palabras se separa entera y la ayuda se sigue leyendo completa', () => {
    const ayuda =
      'Importá el Excel del proveedor. También podés arrastrar la lista completa hasta acá.';

    expect(texto('larga', '.pista-teclado')).toBe(
      'También podés arrastrar la lista completa hasta acá.',
    );
    expect(texto('larga', '.ayuda')).toBe(ayuda);
  });

  it('la ayuda de siempre sigue separando su pista', () => {
    expect(texto('por-defecto', '.pista-teclado')).toBe('También podés arrastrarlo hasta acá.');
    expect(texto('por-defecto', '.ayuda')).toBe(
      'Excel (.xlsx, .xls), CSV o PDF, hasta 25 MB. También podés arrastrarlo hasta acá.',
    );
  });

  it('sin pista, la ayuda queda entera y no hay nada que esconder en táctil', () => {
    expect(texto('sin-pista', '.pista-teclado')).toBe('');
    expect(texto('sin-pista', '.ayuda')).toBe('El remito del proveedor en PDF.');
  });
});
