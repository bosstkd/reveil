import { BrowserPush, BrowserSubscription } from './browser-push';

/** Navigateur simulé pour les tests (autorisation, service worker, PushManager). */
export class FakeBrowserPush implements BrowserPush {
  isSupported = true;
  perm: NotificationPermission = 'default';
  /** Réponse de l'utilisateur à la demande d'autorisation. */
  answer: NotificationPermission = 'granted';
  sub: BrowserSubscription | null = null;
  readonly subscribed: string[] = [];
  permissionRequests = 0;

  supported(): boolean {
    return this.isSupported;
  }

  permission(): NotificationPermission {
    return this.perm;
  }

  async requestPermission(): Promise<NotificationPermission> {
    this.permissionRequests++;
    this.perm = this.answer;
    return this.answer;
  }

  async currentSubscription(): Promise<BrowserSubscription | null> {
    return this.sub;
  }

  async subscribe(key: string): Promise<BrowserSubscription> {
    this.subscribed.push(key);
    this.sub = this.subscription(`https://fcm.googleapis.com/fcm/send/abc-${this.subscribed.length}`, key);
    return this.sub;
  }

  subscription(endpoint: string, key: string | null): BrowserSubscription {
    const sub: BrowserSubscription = {
      endpoint,
      keys: { p256dh: 'BPUB', auth: 'AUTH' },
      applicationServerKey: key,
      unsubscribe: async () => {
        if (this.sub === sub) {
          this.sub = null;
        }
        return true;
      },
    };
    return sub;
  }
}

/** Laisse s'exécuter les promesses en attente (étapes asynchrones entre deux requêtes HTTP). */
export const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
