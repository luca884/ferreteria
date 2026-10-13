import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';
import { AuthService } from './auth.service';

/** Adónde cae cada rol al entrar a `/`: el Vendedor al mostrador, el resto a rubros. */
export const inicioPorRol: RedirectFunction = () =>
  inject(AuthService).sesion()?.rol === 'VENDEDOR' ? 'mostrador' : 'rubros';
