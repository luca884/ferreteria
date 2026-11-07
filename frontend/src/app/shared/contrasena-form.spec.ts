import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ContrasenaForm, NuevaContrasena } from './contrasena-form';

describe('ContrasenaForm', () => {
  let fixture: ComponentFixture<ContrasenaForm>;
  let emitidos: NuevaContrasena[];

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`);

  async function abrir(entradas: Record<string, unknown> = {}) {
    await TestBed.configureTestingModule({ imports: [ContrasenaForm] }).compileComponents();
    fixture = TestBed.createComponent(ContrasenaForm);
    emitidos = [];
    for (const [nombre, valor] of Object.entries(entradas)) fixture.componentRef.setInput(nombre, valor);
    fixture.componentInstance.enviar.subscribe((n) => emitidos.push(n));
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function escribir(id: string, valor: string) {
    input(id)!.value = valor;
    input(id)!.dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  it('sin pedirActual no muestra el campo de la contraseña actual', async () => {
    await abrir();

    expect(input('actual')).toBeNull();
    expect(input('nueva')).not.toBeNull();
    expect(input('confirmacion')).not.toBeNull();
  });

  it('con pedirActual muestra el campo de la contraseña actual', async () => {
    await abrir({ pedirActual: true });

    expect(input('actual')).not.toBeNull();
  });

  it('emite solo la nueva cuando no pide la actual', async () => {
    await abrir();
    await escribir('nueva', 'nueva1234');
    await escribir('confirmacion', 'nueva1234');
    await enviar();

    expect(emitidos).toEqual([{ nueva: 'nueva1234' }]);
  });

  it('con pedirActual emite la actual y la nueva', async () => {
    await abrir({ pedirActual: true });
    await escribir('actual', 'vieja1234');
    await escribir('nueva', 'nueva1234');
    await escribir('confirmacion', 'nueva1234');
    await enviar();

    expect(emitidos).toEqual([{ actual: 'vieja1234', nueva: 'nueva1234' }]);
  });

  it('con pedirActual exige la actual y no emite si falta', async () => {
    await abrir({ pedirActual: true });
    await escribir('nueva', 'nueva1234');
    await escribir('confirmacion', 'nueva1234');
    await enviar();

    expect(emitidos).toEqual([]);
    expect(el().textContent).toContain('Ingresá tu contraseña actual.');
  });

  it('rechaza una nueva de menos de 8 caracteres sin emitir', async () => {
    await abrir();
    await escribir('nueva', '1234567');
    await escribir('confirmacion', '1234567');
    await enviar();

    expect(emitidos).toEqual([]);
    expect(el().textContent).toContain('La contraseña tiene que tener entre 8 y 72 caracteres.');
  });

  it('rechaza una nueva de más de 72 caracteres sin emitir', async () => {
    await abrir();
    await escribir('nueva', 'a'.repeat(73));
    await escribir('confirmacion', 'a'.repeat(73));
    await enviar();

    expect(emitidos).toEqual([]);
    expect(el().textContent).toContain('La contraseña tiene que tener entre 8 y 72 caracteres.');
  });

  it('rechaza una nueva de solo espacios sin emitir y lo avisa', async () => {
    await abrir();
    await escribir('nueva', ' '.repeat(8));
    await escribir('confirmacion', ' '.repeat(8));
    await enviar();

    expect(emitidos).toEqual([]);
    expect(el().querySelector('#nueva-error')?.textContent).toContain('La contraseña no puede ser solo espacios.');
  });

  it('acepta una nueva con espacios si tiene otros caracteres', async () => {
    await abrir();
    await escribir('nueva', 'mi clave 1');
    await escribir('confirmacion', 'mi clave 1');
    await enviar();

    expect(emitidos).toEqual([{ nueva: 'mi clave 1' }]);
  });

  it('acepta justo 8 y justo 72 caracteres', async () => {
    await abrir();
    await escribir('nueva', 'a'.repeat(72));
    await escribir('confirmacion', 'a'.repeat(72));
    await enviar();
    expect(emitidos).toEqual([{ nueva: 'a'.repeat(72) }]);

    await escribir('nueva', 'a'.repeat(8));
    await escribir('confirmacion', 'a'.repeat(8));
    await enviar();
    expect(emitidos.at(-1)).toEqual({ nueva: 'a'.repeat(8) });
  });

  it('avisa si la confirmación no coincide y no emite', async () => {
    await abrir();
    await escribir('nueva', 'nueva1234');
    await escribir('confirmacion', 'nueva9999');
    await enviar();

    expect(emitidos).toEqual([]);
    expect(el().textContent).toContain('Las contraseñas no coinciden.');
  });

  it('muestra el error del servidor', async () => {
    await abrir({ errorServidor: 'No se pudo cambiar la contraseña.' });

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('No se pudo cambiar la contraseña.');
  });

  it('muestra el error de la contraseña actual junto a su campo', async () => {
    await abrir({ pedirActual: true, errorActual: 'La contraseña actual no es correcta.' });

    expect(el().querySelector('#actual-error')?.textContent).toContain('La contraseña actual no es correcta.');
    expect(input('actual')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('mientras envía deshabilita el botón', async () => {
    await abrir({ enviando: true });

    expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
  });

  it('avisa `editado` cuando el usuario escribe en cualquier campo, y no al reiniciar (arreglos-confusos, S8b)', async () => {
    await abrir({ pedirActual: true });
    let editados = 0;
    fixture.componentInstance.editado.subscribe(() => editados++);

    await escribir('actual', 'v');
    await escribir('nueva', 'n');
    await escribir('confirmacion', 'c');
    expect(editados).toBe(3);

    fixture.componentInstance.reiniciar();
    await refrescar();
    expect(editados).toBe(3);
  });

  it('los campos tienen aire entre sí (arreglos-confusos, S8d)', async () => {
    await abrir({ pedirActual: true });

    const form = getComputedStyle(el().querySelector('form')!);
    expect(form.display).toBe('flex');
    expect(form.flexDirection).toBe('column');
    expect(form.getPropertyValue('row-gap') || form.getPropertyValue('gap')).not.toBe('');
  });

  it('reiniciar vacía los campos y los errores', async () => {
    await abrir({ pedirActual: true });
    await escribir('actual', 'vieja1234');
    await escribir('nueva', 'nueva1234');
    await escribir('confirmacion', 'otra');
    await enviar();

    fixture.componentInstance.reiniciar();
    await refrescar();

    expect(input('actual')!.value).toBe('');
    expect(input('nueva')!.value).toBe('');
    expect(input('confirmacion')!.value).toBe('');
    expect(el().textContent).not.toContain('Las contraseñas no coinciden.');
  });
});
