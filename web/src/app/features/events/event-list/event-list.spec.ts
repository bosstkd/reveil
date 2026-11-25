import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { EventList } from './event-list';
import { AuthService } from '../../../core/auth/auth.service';

describe('EventList', () => {
  it('« Se déconnecter » révoque la session de cet appareil et renvoie vers /connexion', () => {
    localStorage.setItem(
      'reveil.session',
      JSON.stringify({ token: 'jwt', refreshToken: 's.r', user: { id: 'u1', email: 'a@b.co' } }),
    );
    TestBed.configureTestingModule({
      imports: [EventList],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(EventList);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('a@b.co');

    el.querySelector<HTMLButtonElement>('[data-testid="logout"]')!.click();
    const req = http.expectOne('api/auth/logout');
    expect(req.request.body).toEqual({ refreshToken: 's.r' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(localStorage.getItem('reveil.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/connexion');
    http.verify();
  });
});
