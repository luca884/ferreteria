import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MapeoColumnas, OpcionColumna } from './mapeo-columnas';
import { CampoDeMapeo, FormMapeo, crearFormMapeo } from './mapeo-lista';

describe('MapeoColumnas', () => {
  let fixture: ComponentFixture<MapeoColumnas>;
  let form: FormMapeo;

  const opciones: OpcionColumna[] = [
    { indice: 0, etiqueta: 'A · Código' },
    { indice: 1, etiqueta: 'B · Artículo' },
    { indice: 2, etiqueta: 'C · Lista (unidad)' },
    { indice: 3, etiqueta: 'D' },
  ];

  async function crear(reconocidos: ReadonlySet<CampoDeMapeo> = new Set()) {
    form = crearFormMapeo();
    await TestBed.configureTestingModule({ imports: [MapeoColumnas] }).compileComponents();
    fixture = TestBed.createComponent(MapeoColumnas);
    fixture.componentRef.setInput('grupo', form);
    fixture.componentRef.setInput('opciones', opciones);
    fixture.componentRef.setInput('reconocidos', reconocidos);
    await asentar();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;

  async function asentar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function elegir(selector: string, texto: string) {
    const select = q<HTMLSelectElement>(selector);
    const opcion = Array.from(select.options).find((o) => o.textContent?.trim() === texto);
    expect(opcion, `${selector} → ${texto}`).toBeDefined();
    select.value = opcion!.value;
    select.dispatchEvent(new Event('change'));
    await asentar();
  }

  const textosDe = (selector: string) =>
    Array.from(q<HTMLSelectElement>(selector).options).map((o) => o.textContent?.trim());
  const seleccionado = (selector: string) => {
    const select = q<HTMLSelectElement>(selector);
    return select.options[select.selectedIndex]?.textContent?.trim();
  };
  const rotuloDe = (id: string) => el().querySelector(`label[for="${id}"]`)?.textContent?.trim();

  describe('campos', () => {
    it('ofrece exactamente los campos de la lista cerrada, en este orden', async () => {
      await crear();

      const ids = Array.from(el().querySelectorAll('select, input')).map((c) => c.id);
      expect(ids).toEqual([
        'filaDesde',
        'columnaCodigo',
        'columnaDescripcion',
        'columnaCosto',
        'columnaMoneda',
        'monedaFija',
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
    });

    it('rotula cada campo: código del proveedor, descripción, costo, moneda, precios con y sin IVA, % IVA, ajustes, barras, unidad y rubro', async () => {
      await crear();

      expect(rotuloDe('filaDesde')).toBe('Primera fila de datos');
      expect(rotuloDe('columnaCodigo')).toBe('Columna del código del proveedor');
      expect(rotuloDe('columnaDescripcion')).toBe('Columna de la descripción (opcional)');
      expect(rotuloDe('columnaCosto')).toBe('Columna del costo');
      expect(rotuloDe('columnaMoneda')).toBe('Columna de la moneda (opcional)');
      expect(rotuloDe('monedaFija')).toBe('Moneda de todas las filas (opcional)');
      expect(rotuloDe('columnaConIva')).toBe('Columna del precio con IVA (opcional)');
      expect(rotuloDe('columnaSinIva')).toBe('Columna del precio sin IVA (opcional, solo para calcular el IVA)');
      expect(rotuloDe('columnaIva')).toBe('Columna del % de IVA (opcional)');
      expect(rotuloDe('columnaAjuste1')).toBe('Columna del descuento o recargo 1 (opcional)');
      expect(rotuloDe('columnaAjuste2')).toBe('Columna del descuento o recargo 2 (opcional)');
      expect(rotuloDe('columnaAjuste3')).toBe('Columna del descuento o recargo 3 (opcional)');
      expect(rotuloDe('columnaCodigoBarras')).toBe('Columna del código de barras (opcional)');
      expect(rotuloDe('columnaUnidad')).toBe('Columna de la unidad (opcional)');
      expect(rotuloDe('columnaRubro')).toBe('Columna del rubro (opcional)');
    });

    it('«Stock» y «Marca» no aparecen en ningún rótulo ni opción de campo', async () => {
      await crear();

      const rotulos = Array.from(el().querySelectorAll('label, legend')).map((l) => l.textContent ?? '');
      expect(rotulos.length).toBeGreaterThan(10);
      expect(rotulos.some((r) => /stock|marca/i.test(r))).toBe(false);
      expect(el().querySelector('#columnaStock, #columnaMarca')).toBeNull();
    });

    it('cada selector ofrece sus columnas con la letra y el texto del encabezado', async () => {
      await crear();

      expect(textosDe('#columnaCodigo')).toEqual([
        'Elegí la columna',
        'A · Código',
        'B · Artículo',
        'C · Lista (unidad)',
        'D',
      ]);
      expect(textosDe('#columnaDescripcion')[0]).toBe('Sin descripción');
      expect(textosDe('#columnaSinIva')[0]).toBe('No usar');
      expect(textosDe('#columnaAjuste3')).toHaveLength(5);
    });

    it('elegir una columna escribe el número de columna en el formulario', async () => {
      await crear();

      await elegir('#columnaCosto', 'C · Lista (unidad)');
      await elegir('#columnaCodigo', 'A · Código');

      expect(form.controls.columnaCosto.value).toBe(2);
      expect(form.controls.columnaCodigo.value).toBe(0);
    });

    it('el formulario escribe en los selectores', async () => {
      await crear();

      form.patchValue({ columnaDescripcion: 1, columnaAjuste2: 3 });
      await asentar();

      expect(seleccionado('#columnaDescripcion')).toBe('B · Artículo');
      expect(seleccionado('#columnaAjuste2')).toBe('D');
    });
  });

  describe('«Reconocida»', () => {
    it('marca solo los campos que vienen de la propuesta', async () => {
      await crear(new Set<CampoDeMapeo>(['columnaCodigo', 'columnaCosto', 'columnaSinIva']));

      const marcados = Array.from(el().querySelectorAll('.reconocida')).map((m) => m.id);
      expect(marcados).toEqual(['columnaCodigo-reconocida', 'columnaCosto-reconocida', 'columnaSinIva-reconocida']);
      expect(q('#columnaCodigo-reconocida').textContent?.trim()).toBe('Reconocida');
      expect(el().querySelector('#columnaDescripcion-reconocida')).toBeNull();
    });

    it('el campo reconocido queda descripto por su marca para los lectores de pantalla', async () => {
      await crear(new Set<CampoDeMapeo>(['columnaCosto']));

      expect(q('#columnaCosto').getAttribute('aria-describedby')).toContain('columnaCosto-reconocida');
      expect(q('#columnaCodigo').getAttribute('aria-describedby') ?? '').not.toContain('reconocida');
    });

    it('sin propuesta no hay ninguna marca', async () => {
      await crear();

      expect(el().querySelectorAll('.reconocida')).toHaveLength(0);
    });

    it('la primera fila de datos también se marca cuando viene de la propuesta', async () => {
      await crear(new Set<CampoDeMapeo>(['filaDesde']));

      expect(q('#filaDesde-reconocida').textContent?.trim()).toBe('Reconocida');
    });
  });

  describe('moneda por columna o fija', () => {
    it('elegir la columna de la moneda limpia la moneda fija', async () => {
      await crear();
      await elegir('#monedaFija', 'Dólares (USD)');
      expect(form.controls.monedaFija.value).toBe('USD');

      await elegir('#columnaMoneda', 'D');

      expect(form.controls.columnaMoneda.value).toBe(3);
      expect(form.controls.monedaFija.value).toBeNull();
      expect(seleccionado('#monedaFija')).toBe('Sin moneda fija');
    });

    it('elegir la moneda fija limpia la columna de la moneda', async () => {
      await crear();
      await elegir('#columnaMoneda', 'D');
      expect(form.controls.columnaMoneda.value).toBe(3);

      await elegir('#monedaFija', 'Pesos (ARS)');

      expect(form.controls.monedaFija.value).toBe('ARS');
      expect(form.controls.columnaMoneda.value).toBeNull();
      expect(seleccionado('#columnaMoneda')).toBe('No usar');
    });

    it('dejar la moneda en «no usar» no toca la otra', async () => {
      await crear();
      await elegir('#monedaFija', 'Dólares (USD)');

      form.controls.columnaMoneda.setValue(null);
      await asentar();

      expect(form.controls.monedaFija.value).toBe('USD');
    });
  });

  describe('IVA: precio sin IVA y con IVA van juntas', () => {
    it('con una sola elegida avisa y marca las dos como inválidas', async () => {
      await crear();

      await elegir('#columnaSinIva', 'C · Lista (unidad)');

      expect(q('#iva-par-error').textContent).toContain('Elegí las dos columnas');
      expect(q('#columnaSinIva').getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaConIva').getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaConIva').getAttribute('aria-describedby')).toContain('iva-par-error');
    });

    it('con las dos elegidas, o ninguna, no avisa', async () => {
      await crear();
      expect(el().querySelector('#iva-par-error')).toBeNull();

      await elegir('#columnaSinIva', 'C · Lista (unidad)');
      await elegir('#columnaConIva', 'D');

      expect(el().querySelector('#iva-par-error')).toBeNull();
      expect(q('#columnaSinIva').hasAttribute('aria-invalid')).toBe(false);
    });

    it('explica para qué sirven y que el precio sin IVA solo sirve para calcular el IVA', async () => {
      await crear();

      const ayuda = q('#iva-ayuda').textContent ?? '';
      expect(ayuda).toContain('% de IVA');
      expect(ayuda).toContain('solo sirve para calcular el IVA');
    });
  });

  describe('descuentos y recargos', () => {
    it('dice con palabras que negativo es descuento y positivo es recargo', async () => {
      await crear();

      const ayuda = q('#ajustes-ayuda').textContent?.replace(/\s+/g, ' ') ?? '';
      expect(ayuda).toContain('negativo es descuento');
      expect(ayuda).toContain('positivo es recargo');
      expect(ayuda).toContain('después de las bonificaciones');
    });

    it('los tres campos de ajuste quedan descriptos por esa explicación', async () => {
      await crear();

      for (const id of ['columnaAjuste1', 'columnaAjuste2', 'columnaAjuste3']) {
        expect(q(`#${id}`).getAttribute('aria-describedby'), id).toContain('ajustes-ayuda');
      }
    });
  });

  describe('obligatorios y accesibilidad', () => {
    it('sin elegir el código ni el costo, tocados, quedan inválidos', async () => {
      await crear();
      expect(q('#columnaCodigo').hasAttribute('aria-invalid')).toBe(false);

      form.markAllAsTouched();
      await asentar();

      expect(q('#columnaCodigo').getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaCosto').getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaDescripcion').hasAttribute('aria-invalid')).toBe(false);
    });

    it('la primera fila de datos fuera de rango queda enlazada a su mensaje', async () => {
      await crear();

      form.controls.filaDesde.setValue(0);
      await asentar();

      expect(q('#filaDesde').getAttribute('aria-invalid')).toBe('true');
      expect(q('#filaDesde').getAttribute('aria-describedby')).toContain('filaDesde-error');
      expect(q('#filaDesde-error').textContent).toContain('Indicá la primera fila de datos (1 o más).');
    });

    it('cada campo tiene su etiqueta asociada, agrupada en un fieldset con leyenda', async () => {
      await crear();

      const campos = Array.from(el().querySelectorAll('select, input'));
      for (const campo of campos) {
        expect(el().querySelector(`label[for="${campo.id}"]`), campo.id).not.toBeNull();
      }
      const leyendas = Array.from(el().querySelectorAll('fieldset > legend')).map((l) => l.textContent?.trim());
      expect(leyendas).toEqual([
        'Datos del artículo',
        'Moneda',
        'IVA',
        'Descuentos y recargos',
        'Otros datos de los artículos nuevos',
      ]);
    });

    it('la primera fila de datos abre el teclado numérico', async () => {
      await crear();

      expect(q('#filaDesde').getAttribute('inputmode')).toBe('numeric');
    });
  });
});
