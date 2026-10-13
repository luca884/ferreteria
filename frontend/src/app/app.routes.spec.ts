import { Route } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { ActualizacionMasiva } from './features/actualizacion-masiva/actualizacion-masiva';
import { ArticuloForm } from './features/articulos/articulo-form';
import { Articulos } from './features/articulos/articulos';
import { Importaciones } from './features/importaciones/importaciones';
import { Proveedores } from './features/proveedores/proveedores';
import { routes } from './app.routes';

describe('rutas', () => {
  const hijas = (): Route[] => routes.find((r) => r.path === '')?.children ?? [];

  it('la ruta proveedores carga el componente Proveedores', async () => {
    const ruta = hijas().find((r) => r.path === 'proveedores');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Proveedores);
  });

  it('la ruta importaciones carga el componente Importaciones', async () => {
    const ruta = hijas().find((r) => r.path === 'importaciones');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Importaciones);
  });

  it('la ruta actualizacion-masiva carga el componente ActualizacionMasiva', async () => {
    const ruta = hijas().find((r) => r.path === 'actualizacion-masiva');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(ActualizacionMasiva);
  });

  it('la ruta actualizacion-masiva no tiene guard propio: solo el authGuard del padre, como importaciones', () => {
    const padre = routes.find((r) => r.path === '');
    const masiva = hijas().find((r) => r.path === 'actualizacion-masiva');
    const importaciones = hijas().find((r) => r.path === 'importaciones');

    expect(masiva).toBeDefined();
    expect(masiva!.canActivate).toBeUndefined();
    expect(masiva!.canActivate).toEqual(importaciones!.canActivate);
    expect(padre!.canActivate).toEqual([authGuard]);
  });

  it('la ruta articulos carga el componente Articulos', async () => {
    const ruta = hijas().find((r) => r.path === 'articulos');

    expect(ruta).toBeDefined();
    expect(await (ruta!.loadComponent as () => Promise<unknown>)()).toBe(Articulos);
  });

  it('las rutas articulos/nuevo y articulos/:id cargan el formulario de artículo', async () => {
    for (const path of ['articulos/nuevo', 'articulos/:id']) {
      const ruta = hijas().find((r) => r.path === path);

      expect(ruta, path).toBeDefined();
      expect(await (ruta!.loadComponent as () => Promise<unknown>)(), path).toBe(ArticuloForm);
    }
  });

  it('articulos/nuevo va antes que articulos/:id para que "nuevo" no se tome como id', () => {
    const paths = hijas().map((r) => r.path);

    expect(paths.indexOf('articulos/nuevo')).toBeGreaterThanOrEqual(0);
    expect(paths.indexOf('articulos/nuevo')).toBeLessThan(paths.indexOf('articulos/:id'));
  });
});
