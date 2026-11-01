import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ImporteInput } from './importe-input';

@Component({
  imports: [ImporteInput, ReactiveFormsModule],
  template: `
    <input id="suelto" appImporte [value]="texto()" (input)="texto.set($any($event.target).value)" />
    <input id="con-control" appImporte [formControl]="control" />
    <input id="entero" appImporte [decimales]="0" />
    <input id="con-signo" appImporte negativos [formControl]="conSigno" />
  `,
})
class Anfitrion {
  readonly texto = signal('');
  readonly control = new FormControl<number | null>(null);
  readonly conSigno = new FormControl<number | null>(null);
}

describe('ImporteInput', () => {
  let fixture: ComponentFixture<Anfitrion>;
  const campo = (id: string) => (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(`#${id}`)!;

  /** Lo que hace el navegador al teclear: cambia el valor, mueve el cursor y avisa con `input`. */
  function teclear(input: HTMLInputElement, texto: string): void {
    for (const letra of texto) {
      const cursor = input.selectionStart ?? input.value.length;
      input.value = input.value.slice(0, cursor) + letra + input.value.slice(input.selectionEnd ?? cursor);
      input.setSelectionRange(cursor + 1, cursor + 1);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }

  beforeEach(async () => {
    fixture = TestBed.createComponent(Anfitrion);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('es un campo de texto con teclado numérico', () => {
    expect(campo('suelto').type).toBe('text');
    expect(campo('suelto').inputMode).toBe('decimal');
    expect(campo('suelto').autocomplete).toBe('off');
  });

  it('al teclear 50000 se ve 50.000 y el (input) del componente ya recibe el texto con puntos', () => {
    teclear(campo('suelto'), '50000');

    expect(campo('suelto').value).toBe('50.000');
    expect(fixture.componentInstance.texto()).toBe('50.000');
    expect(campo('suelto').selectionStart).toBe(6);
  });

  it('el punto tecleado no entra y la coma sí, con hasta dos decimales', () => {
    teclear(campo('suelto'), '1.5');
    expect(campo('suelto').value).toBe('15');

    teclear(campo('suelto'), '00,256');
    expect(campo('suelto').value).toBe('1.500,25');
  });

  it('un dígito en el medio deja el cursor justo después de él', () => {
    const input = campo('suelto');
    teclear(input, '1345');
    input.setSelectionRange(1, 1);

    teclear(input, '2');

    expect(input.value).toBe('12.345');
    expect(input.selectionStart).toBe(2);
  });

  it('con un FormControl, el modelo es el número en pesos', () => {
    teclear(campo('con-control'), '1234,5');

    expect(campo('con-control').value).toBe('1.234,5');
    expect(fixture.componentInstance.control.value).toBe(1234.5);
  });

  it('vacío es null', () => {
    const control = fixture.componentInstance.control;
    control.setValue(10);
    campo('con-control').value = '';
    campo('con-control').dispatchEvent(new Event('input', { bubbles: true }));

    expect(control.value).toBeNull();
  });

  it('el valor que pone el formulario se ve con puntos de miles', () => {
    fixture.componentInstance.control.setValue(50000);
    expect(campo('con-control').value).toBe('50.000');

    fixture.componentInstance.control.setValue(35.06);
    expect(campo('con-control').value).toBe('35,06');

    fixture.componentInstance.control.setValue(null);
    expect(campo('con-control').value).toBe('');
  });

  it('deshabilitar el control deshabilita el campo', () => {
    fixture.componentInstance.control.disable();
    expect(campo('con-control').disabled).toBe(true);
  });

  it('con negativos, el menos adelante da un número negativo; solo el menos todavía es null', () => {
    const control = fixture.componentInstance.conSigno;
    teclear(campo('con-signo'), '-');
    expect(control.value).toBeNull();

    teclear(campo('con-signo'), '1500,5');

    expect(campo('con-signo').value).toBe('-1.500,5');
    expect(control.value).toBe(-1500.5);
  });

  it('sin negativos, el menos no entra', () => {
    teclear(campo('con-control'), '-5');
    expect(campo('con-control').value).toBe('5');
    expect(fixture.componentInstance.control.value).toBe(5);
  });

  it('con 0 decimales la coma no entra', () => {
    teclear(campo('entero'), '12,5');
    expect(campo('entero').value).toBe('125');
  });
});
