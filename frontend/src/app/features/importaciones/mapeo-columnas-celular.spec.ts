import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { MapeoColumnas, OpcionColumna } from './mapeo-columnas';
import { CampoDeMapeo, FormMapeo, crearFormMapeo } from './mapeo-lista';

/**
 * El mapeo de columnas en el celular (movil-sectores T13b, S1, S2, S5, S7): sólo cambia cómo se elige. Cada dato de la
 * lista es una fila que abre una hoja inferior con un radio por columna; las opciones, el formulario y los textos son
 * los mismos que con los `<select>` de escritorio. En la tablet nada de eso entra en el DOM.
 */
describe('MapeoColumnas en el celular (T13b)', () => {
  type Modo = 'celular' | 'tablet';
  type ModoDeCampos = 'todo' | 'datos' | 'ajustes';

  const opciones: OpcionColumna[] = [
    { indice: 0, etiqueta: 'A · Código', encabezado: 'Código', ejemplos: ['4021-B', '4021-C', '7733'] },
    { indice: 1, etiqueta: 'B · Artículo', encabezado: 'Artículo', ejemplos: ['Bulón 3/8', 'Tuerca 3/8'] },
    { indice: 2, etiqueta: 'C · Lista (unidad)', encabezado: 'Lista (unidad)', ejemplos: ['3”', '4”'] },
    { indice: 3, etiqueta: 'D', ejemplos: [] },
  ];

  let fixture: ComponentFixture<MapeoColumnas>;
  let form: FormMapeo;

  async function crear(
    opts: { modo?: Modo; reconocidos?: ReadonlySet<CampoDeMapeo>; campos?: ModoDeCampos; opciones?: OpcionColumna[] } = {},
  ) {
    const modo = opts.modo ?? 'celular';
    vi.stubGlobal('matchMedia', (c: string) => ({
      matches: c.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: c,
    }));
    form = crearFormMapeo();
    await TestBed.configureTestingModule({ imports: [MapeoColumnas] }).compileComponents();
    fixture = TestBed.createComponent(MapeoColumnas);
    fixture.componentRef.setInput('grupo', form);
    fixture.componentRef.setInput('opciones', opts.opciones ?? opciones);
    fixture.componentRef.setInput('reconocidos', opts.reconocidos ?? new Set());
    if (opts.campos) fixture.componentRef.setInput('modo', opts.campos);
    await asentar();
  }

  async function asentar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const qa = (selector: string) => Array.from(el().querySelectorAll<HTMLElement>(selector));
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const fila = (campo: string) => q<HTMLButtonElement>(`button.fila-mapeo#${campo}`)!;
  const hoja = () => q('app-hoja-inferior');
  const radios = () => qa('app-hoja-inferior [role="radio"]');
  const modos: Modo[] = ['celular', 'tablet'];

  // ------------------------------------------------------------------------------ lo que no cambia (pasa en la base)

  describe.each(modos)('el formulario, en %s', (modo) => {
    it('el mapeo es el mismo formulario: escribirlo desde afuera se ve y se sigue pudiendo leer', async () => {
      await crear({ modo });

      form.patchValue({ columnaCodigo: 0, columnaCosto: 3, columnaDescripcion: 1, filaDesde: 2 });
      await asentar();

      expect(form.getRawValue()).toMatchObject({ columnaCodigo: 0, columnaCosto: 3, columnaDescripcion: 1, filaDesde: 2 });
      expect(q<HTMLInputElement>('#filaDesde')!.value).toBe('2');
    });

    it('la primera fila de datos sigue siendo el mismo campo numérico con su mensaje', async () => {
      await crear({ modo });
      const campo = q<HTMLInputElement>('#filaDesde')!;

      expect(campo.type).toBe('number');
      expect(campo.getAttribute('inputmode')).toBe('numeric');
      expect(normal(q('label[for="filaDesde"]')?.textContent)).toBe('Primera fila de datos');
      form.controls.filaDesde.setValue(0);
      await asentar();
      expect(normal(q('#filaDesde-error')?.textContent)).toBe('Indicá la primera fila de datos (1 o más).');
      expect(campo.getAttribute('aria-invalid')).toBe('true');
    });

    it('la moneda fija sigue siendo un select que limpia la columna de la moneda', async () => {
      await crear({ modo });
      form.controls.columnaMoneda.setValue(3);

      const select = q<HTMLSelectElement>('#monedaFija')!;
      select.value = Array.from(select.options).find((o) => normal(o.textContent) === 'Dólares (USD)')!.value;
      select.dispatchEvent(new Event('change'));
      await asentar();

      expect(form.controls.monedaFija.value).toBe('USD');
      expect(form.controls.columnaMoneda.value).toBeNull();
    });

    it('«Reconocida» sale con el mismo texto y el mismo id en los campos que reconoció el servidor', async () => {
      await crear({ modo, reconocidos: new Set<CampoDeMapeo>(['columnaCodigo', 'columnaCosto', 'filaDesde']) });

      const marcas = qa('.etiqueta.reconocida');
      expect(marcas.map((m) => m.id)).toEqual(['filaDesde-reconocida', 'columnaCodigo-reconocida', 'columnaCosto-reconocida']);
      expect(marcas.map((m) => normal(m.textContent))).toEqual(['Reconocida', 'Reconocida', 'Reconocida']);
    });

    it('los textos de ayuda de moneda, IVA y ajustes siguen con sus ids', async () => {
      await crear({ modo });

      expect(normal(q('#moneda-ayuda')?.textContent)).toContain('Se usa una o la otra, no las dos.');
      expect(normal(q('#iva-ayuda')?.textContent)).toContain('El precio sin IVA solo sirve para calcular el IVA');
      expect(normal(q('#ajustes-ayuda')?.textContent)).toContain('negativo es descuento');
      expect(normal(q('#otros-ayuda')?.textContent)).toContain('se cargan solo en los artículos nuevos');
    });

    it('el código y el costo, con el foco ya pasado, quedan inválidos y descriptos igual', async () => {
      await crear({ modo });
      form.markAllAsTouched();
      await asentar();

      expect(q('#columnaCodigo')?.getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaCosto')?.getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaDescripcion')?.hasAttribute('aria-invalid')).toBe(false);
    });

    it('con una sola columna de IVA avisa con el mismo texto', async () => {
      await crear({ modo });
      form.controls.columnaSinIva.setValue(2);
      await asentar();

      expect(normal(q('#iva-par-error')?.textContent)).toBe(
        'Elegí las dos columnas: el precio sin IVA y el precio con IVA, o ninguna de las dos.',
      );
      expect(q('#columnaSinIva')?.getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaConIva')?.getAttribute('aria-invalid')).toBe('true');
    });
  });

  // -------------------------------------------------------------------------------- en la tablet no cambia nada

  describe('en la tablet', () => {
    it('siguen los select de siempre y no hay filas, ni hoja, ni «Datos a mapear»', async () => {
      await crear({ modo: 'tablet' });

      expect(q('button.fila-mapeo')).toBeNull();
      expect(q('app-hoja-inferior')).toBeNull();
      expect(q('select#columnaCodigo')).not.toBeNull();
      expect(qa('fieldset > legend').map((l) => normal(l.textContent))).toEqual([
        'Datos del artículo',
        'Moneda',
        'IVA',
        'Descuentos y recargos',
        'Otros datos de los artículos nuevos',
      ]);
    });

    it('el modo de campos no cambia nada: con «datos» o «ajustes» sigue viéndose todo', async () => {
      await crear({ modo: 'tablet', campos: 'datos' });

      expect(qa('fieldset')).toHaveLength(5);
    });
  });

  // -------------------------------------------------------------------------------- lo propio del celular

  describe('la lista «Datos a mapear»', () => {
    it('el primer grupo se llama «Datos a mapear» y sus datos son filas, no select', async () => {
      await crear();

      expect(normal(q('fieldset > legend')?.textContent)).toBe('Datos a mapear');
      expect(q('select#columnaCodigo')).toBeNull();
      expect(qa('fieldset')[0].querySelectorAll('select')).toHaveLength(0);
      expect(qa('fieldset')[0].querySelectorAll('button.fila-mapeo').length).toBe(3);
    });

    it('cada fila dice el dato con su rótulo de siempre, la columna elegida y tres ejemplos', async () => {
      await crear();
      form.patchValue({ columnaCodigo: 0, columnaDescripcion: 1 });
      await asentar();

      expect(normal(fila('columnaCodigo').querySelector('.fila-mapeo-rotulo')?.textContent)).toBe(
        'Columna del código del proveedor',
      );
      expect(normal(fila('columnaCodigo').querySelector('.fila-mapeo-valor')?.textContent)).toBe('A · Código');
      expect(normal(fila('columnaCodigo').querySelector('.fila-mapeo-ejemplos')?.textContent)).toBe(
        '4021-B · 4021-C · 7733',
      );
      expect(normal(fila('columnaDescripcion').querySelector('.fila-mapeo-valor')?.textContent)).toBe('B · Artículo');
    });

    it('sin columna elegida dice lo mismo que la primera opción del select de siempre', async () => {
      await crear();

      expect(normal(fila('columnaCodigo').querySelector('.fila-mapeo-valor')?.textContent)).toBe('Elegí la columna');
      expect(normal(fila('columnaDescripcion').querySelector('.fila-mapeo-valor')?.textContent)).toBe('Sin descripción');
      expect(normal(fila('columnaCosto').querySelector('.fila-mapeo-valor')?.textContent)).toBe('Elegí la columna');
      expect(fila('columnaCodigo').querySelector('.fila-mapeo-ejemplos')).toBeNull();
    });

    it('lleva «Reconocida» adentro de la fila de lo que el servidor reconoció', async () => {
      await crear({ reconocidos: new Set<CampoDeMapeo>(['columnaCodigo']) });

      expect(normal(fila('columnaCodigo').querySelector('.etiqueta.reconocida')?.textContent)).toBe('Reconocida');
      expect(fila('columnaCosto').querySelector('.reconocida')).toBeNull();
    });

    it('cada fila es un botón de la lista, con la hoja como popup y sin submit', async () => {
      await crear();

      expect(fila('columnaCodigo').type).toBe('button');
      expect(fila('columnaCodigo').getAttribute('aria-haspopup')).toBe('dialog');
    });

    it('un obligatorio sin elegir, tocado, marca la fila como inválida', async () => {
      await crear();
      expect(fila('columnaCodigo').hasAttribute('aria-invalid')).toBe(false);

      form.markAllAsTouched();
      await asentar();

      expect(fila('columnaCodigo').getAttribute('aria-invalid')).toBe('true');
      expect(fila('columnaCosto').getAttribute('aria-invalid')).toBe('true');
      expect(fila('columnaDescripcion').hasAttribute('aria-invalid')).toBe(false);
    });

    it('la primera fila de datos sigue siendo un campo numérico adentro de la lista', async () => {
      await crear();

      expect(qa('fieldset')[0].contains(q('#filaDesde'))).toBe(true);
      expect(q('#filaDesde')?.tagName).toBe('INPUT');
    });

    it('no pone el foco en ningún campo al abrirse', async () => {
      await crear();

      expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();
    });
  });

  describe('la hoja para elegir la columna', () => {
    it('tocar una fila abre una hoja inferior con el rótulo del dato como título', async () => {
      await crear();
      expect(hoja()).toBeNull();

      fila('columnaCodigo').click();
      await asentar();

      expect(hoja()).not.toBeNull();
      expect(normal(q('app-hoja-inferior h2')?.textContent)).toBe('Columna del código del proveedor');
      expect(normal(q('app-hoja-inferior .hoja-inferior-subtitulo')?.textContent)).toBe(
        'Tocá la columna donde está. Se muestran las primeras filas de datos.',
      );
    });

    it('lleva un radio por columna, con la letra, el encabezado y los ejemplos', async () => {
      await crear();

      fila('columnaCodigo').click();
      await asentar();

      expect(q('app-hoja-inferior [role="radiogroup"]')?.getAttribute('aria-label')).toBe('Columna del código del proveedor');
      expect(radios()).toHaveLength(4);
      expect(radios().map((r) => normal(r.querySelector('.opcion-columna-letra')?.textContent))).toEqual(['A', 'B', 'C', 'D']);
      expect(radios().map((r) => normal(r.querySelector('b')?.textContent))).toEqual([
        'Código',
        'Artículo',
        'Lista (unidad)',
        'Columna D',
      ]);
      expect(radios().map((r) => normal(r.querySelector('small')?.textContent))).toEqual([
        '4021-B · 4021-C · 7733',
        'Bulón 3/8 · Tuerca 3/8',
        '3” · 4”',
        '',
      ]);
    });

    it('marca como elegida la columna que ya tiene el dato', async () => {
      await crear();
      form.controls.columnaCodigo.setValue(1);
      await asentar();

      fila('columnaCodigo').click();
      await asentar();

      expect(radios().map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'true', 'false', 'false']);
    });

    it('elegir una columna la escribe en el formulario, cierra la hoja y la fila lo muestra', async () => {
      await crear();

      fila('columnaCosto').click();
      await asentar();
      radios()[2].click();
      await asentar();

      expect(form.controls.columnaCosto.value).toBe(2);
      expect(form.controls.columnaCosto.dirty).toBe(true);
      expect(form.controls.columnaCosto.touched).toBe(true);
      expect(hoja()).toBeNull();
      expect(normal(fila('columnaCosto').querySelector('.fila-mapeo-valor')?.textContent)).toBe('C · Lista (unidad)');
    });

    it('lo opcional suma «No usar esta columna», que deja el dato sin columna', async () => {
      await crear();
      form.controls.columnaDescripcion.setValue(1);
      await asentar();

      fila('columnaDescripcion').click();
      await asentar();
      const ninguna = radios().find((r) => normal(r.textContent) === 'No usar esta columna')!;
      expect(ninguna).toBeDefined();
      expect(ninguna.getAttribute('aria-checked')).toBe('false');
      ninguna.click();
      await asentar();

      expect(form.controls.columnaDescripcion.value).toBeNull();
      expect(normal(fila('columnaDescripcion').querySelector('.fila-mapeo-valor')?.textContent)).toBe('Sin descripción');
    });

    it('con el dato sin columna, «No usar esta columna» sale marcada', async () => {
      await crear();

      fila('columnaDescripcion').click();
      await asentar();

      const ninguna = radios().find((r) => normal(r.textContent) === 'No usar esta columna')!;
      expect(ninguna.getAttribute('aria-checked')).toBe('true');
    });

    it('el código y el costo no tienen «No usar esta columna»: son obligatorios', async () => {
      await crear();

      for (const campo of ['columnaCodigo', 'columnaCosto']) {
        fila(campo).click();
        await asentar();
        expect(radios().some((r) => /No usar/.test(r.textContent ?? '')), campo).toBe(false);
        expect(radios()).toHaveLength(4);
        q<HTMLElement>('app-hoja-inferior .dialogo-fondo')!.click();
        await asentar();
      }
    });

    it('Esc cierra la hoja sin cambiar nada y el foco vuelve a la fila que la abrió', async () => {
      await crear();
      form.controls.columnaCodigo.setValue(0);
      await asentar();

      fila('columnaCodigo').click();
      await asentar();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await asentar();

      expect(hoja()).toBeNull();
      expect(form.controls.columnaCodigo.value).toBe(0);
      expect(document.activeElement).toBe(fila('columnaCodigo'));
    });

    it('elegir una columna también devuelve el foco a la fila', async () => {
      await crear();

      fila('columnaCosto').click();
      await asentar();
      radios()[1].click();
      await asentar();

      expect(document.activeElement).toBe(fila('columnaCosto'));
    });

    it('un toque en el velo cierra la hoja y devuelve el foco', async () => {
      await crear();

      fila('columnaCodigo').click();
      await asentar();
      q<HTMLElement>('app-hoja-inferior .dialogo-fondo')!.click();
      await asentar();

      expect(hoja()).toBeNull();
      expect(document.activeElement).toBe(fila('columnaCodigo'));
    });

    it('al abrir no mueve el foco a un campo de texto', async () => {
      await crear();

      fila('columnaCodigo').click();
      await asentar();

      expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();
      expect(q('app-hoja-inferior')?.contains(document.activeElement)).toBe(true);
    });

    it('la hoja es modal: el foco queda atrapado adentro (TrampaFoco)', async () => {
      await crear();

      fila('columnaCodigo').click();
      await asentar();

      expect(q('app-hoja-inferior [role="dialog"]')?.getAttribute('aria-modal')).toBe('true');
      // Con Tab desde el último control vuelve al primero: no se escapa al fondo.
      const controles = radios();
      controles[controles.length - 1].focus();
      const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      document.dispatchEvent(tab);
      expect(tab.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(controles[0]);
    });

    it('elegir en una fila no cambia el resto del mapeo', async () => {
      await crear();
      form.patchValue({ columnaCodigo: 0, columnaCosto: 3, filaDesde: 2 });
      await asentar();

      fila('columnaDescripcion').click();
      await asentar();
      radios().find((r) => normal(r.textContent).startsWith('B'))!.click();
      await asentar();

      expect(form.getRawValue()).toMatchObject({
        columnaCodigo: 0,
        columnaDescripcion: 1,
        columnaCosto: 3,
        filaDesde: 2,
        monedaFija: null,
      });
    });

    it('la columna de la moneda elegida desde la hoja limpia la moneda fija, como con el select', async () => {
      await crear();
      form.controls.monedaFija.setValue('USD');
      await asentar();

      fila('columnaMoneda').click();
      await asentar();
      radios().find((r) => normal(r.textContent).startsWith('D'))!.click();
      await asentar();

      expect(form.controls.columnaMoneda.value).toBe(3);
      expect(form.controls.monedaFija.value).toBeNull();
    });

    it('sin encabezado ni ejemplos la fila dice «Columna D» y no inventa nada más', async () => {
      await crear({ opciones: [{ indice: 0, etiqueta: 'A' }] });

      fila('columnaCodigo').click();
      await asentar();

      expect(normal(radios()[0].querySelector('b')?.textContent)).toBe('Columna A');
      expect(radios()[0].querySelector('small')?.textContent?.trim() ?? '').toBe('');
    });
  });

  describe('los campos de la lista, por modo', () => {
    it('«datos» deja sólo el primer grupo', async () => {
      await crear({ campos: 'datos' });

      expect(qa('fieldset > legend').map((l) => normal(l.textContent))).toEqual(['Datos a mapear']);
      expect(q('#monedaFija')).toBeNull();
    });

    it('«ajustes» deja moneda, IVA, descuentos y otros, con filas y la moneda fija', async () => {
      await crear({ campos: 'ajustes' });

      expect(qa('fieldset > legend').map((l) => normal(l.textContent))).toEqual([
        'Moneda',
        'IVA',
        'Descuentos y recargos',
        'Otros datos de los artículos nuevos',
      ]);
      expect(q('#filaDesde')).toBeNull();
      expect(qa('button.fila-mapeo').map((b) => b.id)).toEqual([
        'columnaMoneda',
        'columnaConIva',
        'columnaSinIva',
        'columnaIva',
        'columnaAjuste1',
        'columnaAjuste2',
        'columnaAjuste3',
        'columnaCodigoBarras',
        'columnaUnidad',
        'columnaRubro',
      ]);
      expect(q('select#monedaFija')).not.toBeNull();
    });

    it('«todo», lo de siempre en el celular, trae los cinco grupos con filas', async () => {
      await crear({ campos: 'todo' });

      expect(qa('fieldset')).toHaveLength(5);
      expect(qa('button.fila-mapeo')).toHaveLength(13);
      expect(qa('select').map((s) => s.id)).toEqual(['monedaFija']);
    });

    it('lo opcional de «ajustes» también ofrece «No usar esta columna»', async () => {
      await crear({ campos: 'ajustes' });
      form.controls.columnaAjuste2.setValue(3);
      await asentar();

      fila('columnaAjuste2').click();
      await asentar();
      radios().find((r) => normal(r.textContent) === 'No usar esta columna')!.click();
      await asentar();

      expect(form.controls.columnaAjuste2.value).toBeNull();
    });
  });
});
