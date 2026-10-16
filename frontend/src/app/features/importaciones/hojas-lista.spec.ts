import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HojaDeLista, HojasLista } from './hojas-lista';

describe('HojasLista', () => {
  let fixture: ComponentFixture<HojasLista>;
  let cambios: number[][];

  const hojas: HojaDeLista[] = [
    { indice: 0, nombre: 'TORNILLOS' },
    { indice: 1, nombre: 'TUERCAS' },
    { indice: 2, nombre: 'Hoja nueva' },
  ];

  async function crear(elegidas: number[], lista = hojas) {
    await TestBed.configureTestingModule({ imports: [HojasLista] }).compileComponents();
    fixture = TestBed.createComponent(HojasLista);
    fixture.componentRef.setInput('hojas', lista);
    fixture.componentRef.setInput('elegidas', elegidas);
    cambios = [];
    fixture.componentInstance.elegidasCambian.subscribe((e) => cambios.push(e));
    await asentar();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const casillas = () => Array.from(el().querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));
  const marcadas = () => casillas().map((c) => c.checked);

  async function asentar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const boton = (texto: string) =>
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === texto)!;

  it('una casilla por hoja, rotulada con el nombre de la hoja', async () => {
    await crear([0]);

    const rotulos = casillas().map((c) => el().querySelector(`label[for="${c.id}"]`)?.textContent?.trim());
    expect(rotulos).toEqual(['TORNILLOS', 'TUERCAS', 'Hoja nueva']);
  });

  it('están marcadas las hojas elegidas y una hoja nueva queda sin marcar', async () => {
    await crear([0, 1]);

    expect(marcadas()).toEqual([true, true, false]);
  });

  it('sin ninguna elegida, todas quedan sin marcar', async () => {
    await crear([]);

    expect(marcadas()).toEqual([false, false, false]);
  });

  it('marcar una hoja emite las elegidas con ella, en orden', async () => {
    await crear([1]);

    casillas()[0].click();
    await asentar();

    expect(cambios).toEqual([[0, 1]]);
  });

  it('desmarcar una hoja la saca de las elegidas', async () => {
    await crear([0, 1, 2]);

    casillas()[1].click();
    await asentar();

    expect(cambios).toEqual([[0, 2]]);
  });

  it('«Marcar todas» y «Desmarcar todas» emiten todas o ninguna', async () => {
    await crear([1]);

    boton('Marcar todas').click();
    boton('Desmarcar todas').click();

    expect(cambios).toEqual([[0, 1, 2], []]);
  });

  it('cuenta las hojas marcadas y lo anuncia', async () => {
    await crear([0, 2]);

    expect(q('.hojas-cuenta').textContent?.trim()).toBe('2 de 3 hojas marcadas');
    expect(q('.hojas-cuenta').getAttribute('aria-live')).toBe('polite');
  });

  it('usa singular con una sola hoja marcada', async () => {
    await crear([1]);

    expect(q('.hojas-cuenta').textContent?.trim()).toBe('1 de 3 hojas marcadas');
  });

  it('la lista es un grupo con leyenda y las casillas siguen el orden de las hojas', async () => {
    await crear([0]);

    expect(q('fieldset > legend').textContent?.trim()).toBe('Hojas que se importan');
    expect(casillas().map((c) => c.id)).toEqual(['hoja-0', 'hoja-1', 'hoja-2']);
  });

  it('con 70 hojas arma las 70 casillas', async () => {
    const muchas = Array.from({ length: 70 }, (_, indice) => ({ indice, nombre: `Rubro ${indice + 1}` }));
    await crear([0], muchas);

    expect(casillas()).toHaveLength(70);
    expect(q('.hojas-cuenta').textContent?.trim()).toBe('1 de 70 hojas marcadas');
  });
});
