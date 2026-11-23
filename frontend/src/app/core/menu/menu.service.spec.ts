import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DISPOSICION_ORIGINAL, Disposicion } from '../../layout/menu';
import { MenuService } from './menu.service';

describe('MenuService', () => {
  let service: MenuService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(MenuService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it.each(['USUARIO', 'ROL'])('lee la disposición de origen %s con GET /api/menu', (origen) => {
    const disposicion: Disposicion = {
      grupos: [{ id: 'mi-grupo', rotulo: 'Mi grupo', tono: 'ventas', claves: ['caja'] }],
      ocultas: ['remitos'],
    };
    let leida: Disposicion | undefined;
    service.obtener().subscribe((resultado: Disposicion) => (leida = resultado));

    http.expectOne({ method: 'GET', url: '/api/menu' }).flush({ origen, disposicion });

    expect(leida).toEqual(disposicion);
  });

  it('usa la disposición original si el servidor devuelve null', () => {
    let leida: Disposicion | undefined;
    service.obtener().subscribe((resultado: Disposicion) => (leida = resultado));

    http.expectOne({ method: 'GET', url: '/api/menu' }).flush({ origen: 'ORIGINAL', disposicion: null });

    expect(leida).toBe(DISPOSICION_ORIGINAL);
  });
});
