import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BROWSER_PUSH, BrowserSubscription } from './browser-push';

/**
 * État des notifications du navigateur :
 * - `checking` : vérification en cours ;
 * - `unsupported` : navigateur sans Web Push (ou page hors HTTPS) ;
 * - `denied` : autorisation refusée (seul l'utilisateur peut la rétablir, dans les réglages du navigateur) ;
 * - `disabled` / `enabled` : notifications désactivées / actives sur ce navigateur pour ce compte.
 */
export type WebPushStatus = 'checking' | 'unsupported' | 'denied' | 'disabled' | 'enabled';

export const PERMISSION_DISMISSED_MESSAGE =
  'Autorisation non accordée : cliquez de nouveau sur « Activer les notifications » puis choisissez « Autoriser ».';
export const ENABLE_FAILED_MESSAGE = 'L’activation des notifications a échoué. Réessayez dans un instant.';
export const DISABLE_FAILED_MESSAGE = 'La désactivation a échoué. Réessayez dans un instant.';

/** Notifications des rappels dans le navigateur (Web Push, service worker `reveil-sw.js`) — story 14. */
@Injectable({ providedIn: 'root' })
export class WebPushService {
  private readonly http = inject(HttpClient);
  private readonly browser = inject(BROWSER_PUSH);

  readonly status = signal<WebPushStatus>('checking');
  readonly busy = signal(false);
  /** Message à afficher (autorisation ignorée, échec réseau) ; null sinon. */
  readonly notice = signal<string | null>(null);

  /**
   * Lit l'état du navigateur. Abonnement existant : il est renvoyé à l'API (rattaché à la session courante,
   * renouvelé si la clé du serveur a changé), pour que l'état affiché corresponde à ce qui sera envoyé.
   */
  async refresh(): Promise<void> {
    if (!this.browser.supported()) {
      this.status.set('unsupported');
      return;
    }
    if (this.browser.permission() === 'denied') {
      this.status.set('denied');
      return;
    }
    try {
      const current = await this.browser.currentSubscription();
      if (!current || this.browser.permission() !== 'granted') {
        this.status.set('disabled');
        return;
      }
      await this.register(await this.ensureKey(current));
      this.status.set('enabled');
    } catch {
      this.status.set('disabled');
    }
  }

  /** Demande l'autorisation (depuis le clic), abonne le navigateur et enregistre l'abonnement côté serveur. */
  async enable(): Promise<void> {
    if (this.busy() || !this.browser.supported()) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    try {
      // En premier, avant tout appel asynchrone : certains navigateurs exigent un geste de l'utilisateur.
      const permission = await this.browser.requestPermission();
      if (permission === 'denied') {
        this.status.set('denied');
        return;
      }
      if (permission !== 'granted') {
        this.status.set('disabled');
        this.notice.set(PERMISSION_DISMISSED_MESSAGE);
        return;
      }
      const existing = await this.browser.currentSubscription();
      await this.register(existing ? await this.ensureKey(existing) : await this.subscribeWithServerKey());
      this.status.set('enabled');
    } catch {
      this.status.set('disabled');
      this.notice.set(ENABLE_FAILED_MESSAGE);
    } finally {
      this.busy.set(false);
    }
  }

  /** Désabonne ce navigateur (serveur puis navigateur) : plus aucun rappel n'y est affiché. */
  async disable(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    try {
      const current = await this.browser.currentSubscription();
      if (current) {
        await firstValueFrom(
          this.http.delete<void>('api/push/web/subscription', { body: { endpoint: current.endpoint } }),
        );
        await current.unsubscribe();
      }
      this.status.set('disabled');
    } catch {
      this.notice.set(DISABLE_FAILED_MESSAGE);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Déconnexion / suppression du compte : le navigateur est désabonné localement, sans appel à l'API
   * (côté serveur, l'abonnement est lié à la session qui se ferme). Sans effet si non pris en charge.
   */
  async forgetBrowser(): Promise<void> {
    try {
      if (this.browser.supported()) {
        await (await this.browser.currentSubscription())?.unsubscribe();
      }
    } catch {
      // sans conséquence : le serveur n'utilise plus l'abonnement d'une session fermée
    }
    this.status.set('checking');
  }

  private async serverKey(): Promise<string> {
    return (await firstValueFrom(this.http.get<{ publicKey: string }>('api/push/web/public-key'))).publicKey;
  }

  private async subscribeWithServerKey(): Promise<BrowserSubscription> {
    return this.browser.subscribe(await this.serverKey());
  }

  /** Abonnement créé avec une autre clé VAPID (serveur réinstallé) : inutilisable, il est recréé. */
  private async ensureKey(subscription: BrowserSubscription): Promise<BrowserSubscription> {
    const key = await this.serverKey();
    if (subscription.applicationServerKey && subscription.applicationServerKey !== key) {
      await subscription.unsubscribe();
      return this.browser.subscribe(key);
    }
    return subscription;
  }

  private register(subscription: BrowserSubscription): Promise<void> {
    return firstValueFrom(
      this.http.put<void>('api/push/web/subscription', {
        endpoint: subscription.endpoint,
        keys: subscription.keys,
      }),
    );
  }
}
