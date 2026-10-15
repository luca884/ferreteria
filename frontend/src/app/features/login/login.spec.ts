import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let navegarA: ReturnType<typeof vi.spyOn>;

  async function crear(returnUrl?: string) {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { login: vi.fn(() => of({})) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(returnUrl === undefined ? {} : { returnUrl }) },
          },
        },
      ],
    }).compileComponents();
    navegarA = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function ingresar() {
    const el = fixture.nativeElement as HTMLElement;
    for (const [id, valor] of [
      ['email', 'dueno@tornillo.demo'],
      ['password', 'demo1234'],
    ]) {
      const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = valor;
      input.dispatchEvent(new Event('input'));
    }
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('sin returnUrl entra al inicio de su rol', async () => {
    await crear();

    await ingresar();

    expect(navegarA).toHaveBeenCalledWith('/');
  });

  it('vuelve a la URL protegida a la que había entrado sin sesión', async () => {
    await crear('/articulos/5?x=1');

    await ingresar();

    expect(navegarA).toHaveBeenCalledWith('/articulos/5?x=1');
  });

  it.each(['https://otro.sitio', '//otro.sitio/x', 'articulos', '/login'])(
    'ignora un returnUrl que no es una ruta propia (%s)',
    async (returnUrl) => {
      await crear(returnUrl);

      await ingresar();

      expect(navegarA).toHaveBeenCalledWith('/');
    },
  );
});
