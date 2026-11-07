import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { DialogoDetalle } from '../../shared/dialogo-detalle';
import { CajaCerrada, DetalleCaja, fechaHoraLocal, textoDiferencia } from './caja.models';
import { CajaService } from './caja.service';
import { ResumenCaja } from './resumen-caja';

const TAMANIO_PAGINA = 20;

/** Historial de cajas cerradas (solo el Dueño), más nuevas primero, con el detalle de la que se elija en un pop-up. */
@Component({
  selector: 'app-historial-cajas',
  imports: [BarraAcciones, DialogoDetalle, MontoPipe, ResumenCaja, RouterLink],
  templateUrl: './historial-cajas.html',
  styleUrl: './historial-cajas.css',
})
export class HistorialCajas implements OnInit {
  private readonly cajas = inject(CajaService);
  protected readonly auth = inject(AuthService);

  protected readonly pagina = signal<PageResponse<CajaCerrada> | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly elegida = signal<number | null>(null);
  protected readonly detalle = signal<DetalleCaja | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);
  /** El resumen abierto: el «Imprimir» del pie del pop-up imprime su hoja. */
  protected readonly resumen = viewChild<ResumenCaja>('resumen');

  /** El título del pop-up sale de la fila: así se sabe qué caja se abrió aunque el detalle esté cargando. */
  protected readonly tituloDetalle = computed(() => {
    const id = this.elegida();
    const caja = this.pagina()?.items.find((c) => c.id === id);
    return caja ? `Caja del ${this.fechaHora(caja.fechaApertura)}` : 'Detalle de la caja';
  });

  protected readonly fechaHora = fechaHoraLocal;
  protected readonly textoDiferencia = textoDiferencia;

  ngOnInit(): void {
    if (this.auth.esDueno()) this.cargar(0);
  }

  protected cargar(numero: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.cajas.historial(numero, TAMANIO_PAGINA).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
      },
      error: (err: unknown) => {
        this.cargando.set(false);
        this.error.set(mensajeDe(err, 'No se pudo cargar el historial de cajas.'));
      },
    });
  }

  protected cerrarDetalle(): void {
    this.elegida.set(null);
    this.detalle.set(null);
    this.errorDetalle.set(null);
  }

  protected elegir(caja: CajaCerrada): void {
    this.elegida.set(caja.id);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.cajas.detalle(caja.id).subscribe({
      next: (detalle) => {
        // Si mientras tanto se eligió otra caja, esta respuesta ya no importa.
        if (this.elegida() === caja.id) this.detalle.set(detalle);
      },
      error: (err: unknown) => {
        if (this.elegida() === caja.id) this.errorDetalle.set(mensajeDe(err, 'No se pudo cargar el detalle de la caja.'));
      },
    });
  }
}
