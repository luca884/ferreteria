// URL base del backend, relativa al origen: el mismo build sirve en local y en el deploy.
// En local la reenvía a localhost:8080 el proxy de ng serve (proxy.conf.json); en Railway, Caddy (frontend/Caddyfile).
export const environment = {
  apiUrl: '/api',
};
