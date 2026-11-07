import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { AL_CERRAR_SESION } from './core/auth/al-cerrar-sesion';
import { authInterceptor } from './core/auth/auth.interceptor';
import { borrarEtiquetasGuardadas } from './features/etiquetas/etiquetas.storage';
import { provideRuedaSinCambiarNumeros } from './core/campos/rueda-en-numeros';

// Argentina: coma decimal y punto de miles (170,00 / 1.234,50) en todos los pipes de número.
registerLocaleData(localeEsAr);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    { provide: LOCALE_ID, useValue: 'es-AR' },
    provideRuedaSinCambiarNumeros(),
    // Lo que cada pantalla guarda del usuario en el navegador y se va con la sesión.
    { provide: AL_CERRAR_SESION, multi: true, useValue: borrarEtiquetasGuardadas },
  ],
};
