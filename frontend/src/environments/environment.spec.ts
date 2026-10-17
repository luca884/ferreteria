import { environment } from './environment';

describe('environment', () => {
  // La API se pide al mismo origen: en local la reenvía el proxy de ng serve y en el deploy, el rewrite del hosting.
  // Una URL absoluta (localhost:8080) en el build hace que el navegador del cliente le pida la API a su propia máquina.
  it('apiUrl es relativa al origen, sin host', () => {
    expect(environment.apiUrl).toBe('/api');
  });
});
