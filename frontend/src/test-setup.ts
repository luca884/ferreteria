/**
 * Setup global de los tests. Fija la zona horaria del proceso en UTC, distinta de Buenos Aires (-03): así los tests de
 * fechas detectan cualquier conversión de zona (las fechas del servidor llegan sin offset y se leen del texto).
 * Tiene que correr antes de que se cree cualquier Date.
 */
const nodo = globalThis as unknown as { process: { env: Record<string, string | undefined> } };
nodo.process.env['TZ'] = 'UTC';
