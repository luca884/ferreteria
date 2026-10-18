import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Cliente, ClienteRequest } from './cliente.models';
import { Clientes } from './clientes';
import { ClientesService } from './clientes.service';

describe('Clientes', () => {
  const ana: Cliente = {
    id: 1,
    razonSocial: 'Ana Gómez',
    tipoDocumento: 'DNI',
    numeroDocumento: '12345678',
    condicionIva: 'CONSUMIDOR_FINAL',
    telefono: '2234567890',
    activo: true,
  };
  const norte: Cliente = {
    id: 2,
    razonSocial: 'Ferretería Norte SRL',
    tipoDocumento: 'CUIT',
    numeroDocumento: '30712345671',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    email: 'norte@correo.com',
    activo: true,
  };
  const viejo: Cliente = { id: 3, razonSocial: 'Pedro Viejo', condicionIva: 'MONOTRIBUTISTA', activo: false };

  const pagina = (items: Cliente[], page = 0, totalPages = 1): PageResponse<Cliente> => ({
    items,
    page,
    size: 20,
    totalElements: items.length,
    totalPages,
  });

  let fixture: ComponentFixture<Clientes>;
  let servicio: {
    buscar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    cambiarActivo: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  async function crear(lista: Cliente[] = [ana, norte, viejo], dueno = true, buscar = vi.fn(() => of(pagina(lista)))) {
    servicio = {
      buscar,
      crear: vi.fn((r: ClienteRequest) => of({ id: 99, activo: true, ...r })),
      actualizar: vi.fn((id: number, r: ClienteRequest) => of({ id, activo: true, ...r })),
      cambiarActivo: vi.fn((id: number, activo: boolean) =>
        of({ ...([ana, norte, viejo].find((c) => c.id === id) as Cliente), activo }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [Clientes],
      providers: [
        { provide: ClientesService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Clientes);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLTableRowElement>('tbody tr'));
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const barra = (selector: string) => el().querySelector<HTMLButtonElement>(`app-barra-acciones ${selector}`);
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === nombre);

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegirEn(id: string, valor: string) {
    select(id).value = valor;
    select(id).dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function enviar(formulario: string) {
    el().querySelector(formulario)!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function elegir(fila: number) {
    (filas()[fila].querySelector('td') as HTMLElement).click();
    await refrescar();
  }

  describe('lista', () => {
    it('al abrir trae la primera página y muestra razón social, documento, condición de IVA y estado', async () => {
      await crear();

      expect(servicio.buscar).toHaveBeenCalledWith({ q: '', soloActivos: false, page: 0 });
      expect(filas()).toHaveLength(3);
      expect(filas()[0].textContent).toContain('Ana Gómez');
      expect(filas()[0].textContent).toContain('DNI 12.345.678');
      expect(filas()[0].textContent).toContain('Consumidor final');
      expect(filas()[1].textContent).toContain('CUIT 30-71234567-1');
      expect(filas()[1].textContent).toContain('Responsable inscripto');
      expect(filas()[2].textContent).toContain('Inactivo');
    });

    it('sin clientes dice que todavía no hay', async () => {
      await crear([]);

      expect(texto('.estado-vacio')).toBe('Todavía no hay clientes.');
    });

    it('si la lista no se pudo traer avisa y Reintentar la vuelve a pedir', async () => {
      const buscar = vi.fn().mockReturnValueOnce(throwError(() => errorHttp(500, 'x'))).mockReturnValue(of(pagina([ana])));
      await crear([], true, buscar);
      expect(texto('[role="alert"]')).toContain('No se pudieron cargar los clientes.');

      (el().querySelector('.reintentar') as HTMLButtonElement).click();
      await refrescar();

      expect(filas()).toHaveLength(1);
    });
  });

  describe('búsqueda y páginas', () => {
    it('buscar manda q y vuelve a la página 0', async () => {
      await crear();
      await escribir('q', '  perez ');

      await enviar('form.filtros');

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: 'perez', soloActivos: false, page: 0 });
    });

    it('«Solo activos» filtra por activos', async () => {
      await crear();
      input('solo-activos').click();
      await refrescar();

      await enviar('form.filtros');

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: '', soloActivos: true, page: 0 });
    });

    it('Limpiar saca el texto y el filtro y vuelve a pedir todo', async () => {
      await crear();
      await escribir('q', 'norte');
      input('solo-activos').click();
      await refrescar();

      boton('Limpiar')!.click();
      await refrescar();

      expect(servicio.buscar).toHaveBeenLastCalledWith({ q: '', soloActivos: false, page: 0 });
      expect(input('q').value).toBe('');
      expect(input('solo-activos').checked).toBe(false);
    });

    it('con más de una página muestra Anterior y Siguiente, y Siguiente pide la página 1 con el mismo filtro', async () => {
      const buscar = vi.fn(() => of(pagina([ana], 0, 3)));
      await crear([], true, buscar);
      await escribir('q', 'a');
      await enviar('form.filtros');

      boton('Siguiente')!.click();
      await refrescar();

      expect(buscar).toHaveBeenLastCalledWith({ q: 'a', soloActivos: false, page: 1 });
      expect(texto('nav.paginas')).toContain('Página 1 de 3');
      expect(boton('Anterior')!.disabled).toBe(true);
    });

    it('con una sola página no hay navegación', async () => {
      await crear();

      expect(el().querySelector('nav.paginas')).toBeNull();
    });
  });

  describe('alta y edición', () => {
    it('crea con la razón social obligatoria y manda solo lo cargado, sin espacios de más', async () => {
      await crear();
      await escribir('razon-social', '  Juan Pérez  ');
      await escribir('telefono', ' 223 555 ');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalledWith({
        razonSocial: 'Juan Pérez',
        condicionIva: 'CONSUMIDOR_FINAL',
        telefono: '223 555',
      });
      expect(texto('.alerta.exito')).toBe('Se guardó «Juan Pérez».');
    });

    it('manda el documento completo con su tipo, y la condición de IVA elegida', async () => {
      await crear();
      await escribir('razon-social', 'Ferretería Sur');
      await elegirEn('tipo-documento', 'CUIT');
      await escribir('numero-documento', '30-71234567-1');
      await elegirEn('condicion-iva', 'RESPONSABLE_INSCRIPTO');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalledWith({
        razonSocial: 'Ferretería Sur',
        tipoDocumento: 'CUIT',
        numeroDocumento: '30-71234567-1',
        condicionIva: 'RESPONSABLE_INSCRIPTO',
      });
    });

    it('sin razón social no llama al servicio y marca el campo', async () => {
      await crear();

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(input('razon-social').getAttribute('aria-invalid')).toBe('true');
      expect(texto('#razon-social-error')).toBe('La razón social es obligatoria.');
    });

    it('el número sin tipo, o el tipo sin número, se marca antes de mandar', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await escribir('numero-documento', '12345678');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#tipo-documento-error')).toBe('Elegí el tipo de documento.');
    });

    it('el tipo sin número pide el número', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await elegirEn('tipo-documento', 'DNI');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#numero-documento-error')).toBe('Falta el número de documento.');
    });

    it('un correo que no parece correo se marca', async () => {
      await crear();
      await escribir('razon-social', 'Ana');
      await escribir('email', 'no-es-correo');

      await enviar('form.alta');

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(texto('#email-error')).toBe('El correo no es válido.');
    });

    it('guardado el alta, el formulario queda vacío y la lista se vuelve a pedir', async () => {
      await crear();
      await escribir('razon-social', 'Juan');
      const pedidos = servicio.buscar.mock.calls.length;

      await enviar('form.alta');

      expect(input('razon-social').value).toBe('');
      expect(servicio.buscar.mock.calls.length).toBe(pedidos + 1);
    });

    it('los errores por campo del servidor (400) se listan', async () => {
      await crear();
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(400, 'Datos inválidos', { numeroDocumento: 'El CUIT no es válido: revisá el dígito verificador.' })),
      );
      await escribir('razon-social', 'Ana');
      await elegirEn('tipo-documento', 'CUIT');
      await escribir('numero-documento', '20123456787');

      await enviar('form.alta');

      expect(texto('.errores-campos')).toContain('El CUIT no es válido: revisá el dígito verificador.');
    });

    it('un documento repetido (409) muestra el mensaje del servidor y conserva lo escrito', async () => {
      await crear();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(409, 'Ya hay un cliente con ese documento.')));
      await escribir('razon-social', 'Ana');

      await enviar('form.alta');

      expect(texto('p.error')).toBe('Ya hay un cliente con ese documento.');
      expect(input('razon-social').value).toBe('Ana');
    });

    it('Editar precarga el cliente, y guardar llama a actualizar con su id', async () => {
      await crear();
      await elegir(1);

      barra('.editar')!.click();
      await refrescar();

      expect(input('razon-social').value).toBe('Ferretería Norte SRL');
      expect(select('tipo-documento').value).toBe('CUIT');
      expect(input('numero-documento').value).toBe('30712345671');
      expect(select('condicion-iva').value).toBe('RESPONSABLE_INSCRIPTO');
      expect(input('email').value).toBe('norte@correo.com');
      expect(texto('form.alta h2')).toBe('Editar cliente');

      await escribir('razon-social', 'Ferretería Norte SA');
      await enviar('form.alta');

      expect(servicio.actualizar).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ razonSocial: 'Ferretería Norte SA', tipoDocumento: 'CUIT', numeroDocumento: '30712345671' }),
      );
    });

    it('al editar se puede sacar el documento: no se manda ni tipo ni número', async () => {
      await crear();
      await elegir(0);
      barra('.editar')!.click();
      await refrescar();
      await elegirEn('tipo-documento', '');
      await escribir('numero-documento', '');

      await enviar('form.alta');

      const pedido = servicio.actualizar.mock.calls[0][1] as ClienteRequest;
      expect('tipoDocumento' in pedido).toBe(false);
      expect('numeroDocumento' in pedido).toBe(false);
    });

    it('Cancelar vuelve al alta y vacía el formulario', async () => {
      await crear();
      await elegir(0);
      barra('.editar')!.click();
      await refrescar();

      boton('Cancelar')!.click();
      await refrescar();

      expect(texto('form.alta h2')).toBe('Nuevo cliente');
      expect(input('razon-social').value).toBe('');
    });

    it('el Vendedor también crea y edita', async () => {
      await crear([ana], false);
      await escribir('razon-social', 'Juan');

      await enviar('form.alta');

      expect(servicio.crear).toHaveBeenCalled();
      expect(barra('.editar')).not.toBeNull();
    });
  });

  describe('desactivar (solo el Dueño)', () => {
    it('el Dueño desactiva al elegido y la fila pasa a Inactivo', async () => {
      await crear();
      await elegir(0);

      barra('.desactivar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(1, false);
      expect(filas()[0].textContent).toContain('Inactivo');
    });

    it('en un cliente inactivo el botón dice Reactivar y lo reactiva', async () => {
      await crear();
      await elegir(2);

      expect(barra('.desactivar')!.textContent!.trim()).toBe('Reactivar');
      barra('.desactivar')!.click();
      await refrescar();

      expect(servicio.cambiarActivo).toHaveBeenCalledWith(3, true);
    });

    it('el Vendedor no ve el botón de desactivar', async () => {
      await crear([ana], false);

      expect(barra('.desactivar')).toBeNull();
    });

    it('sin elegir a nadie, el botón está apagado', async () => {
      await crear();

      expect(barra('.desactivar')!.disabled).toBe(true);
    });

    it('si el servidor falla al desactivar, avisa y la fila no cambia', async () => {
      await crear();
      servicio.cambiarActivo.mockReturnValue(throwError(() => errorHttp(403, 'No permitido')));
      await elegir(0);

      barra('.desactivar')!.click();
      await refrescar();

      expect(texto('p.error')).toBe('No permitido');
      expect(filas()[0].textContent).toContain('Activo');
    });
  });
});
