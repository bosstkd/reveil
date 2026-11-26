// Firebase Cloud Messaging est un module natif : remplacé dans les tests par un double contrôlable.
// Les tests déclenchent les écouteurs via `__emit` (appui sur une notification, nouveau jeton…).
jest.mock('@react-native-firebase/messaging', () => {
  const listeners: Record<string, Set<(value: unknown) => void>> = { opened: new Set(), message: new Set(), token: new Set() };
  const subscribe = (kind: string) => (_messaging: unknown, listener: (value: unknown) => void) => {
    listeners[kind].add(listener);
    return () => listeners[kind].delete(listener);
  };
  return {
    __esModule: true,
    AuthorizationStatus: { NOT_DETERMINED: -1, DENIED: 0, AUTHORIZED: 1, PROVISIONAL: 2, EPHEMERAL: 3 },
    getMessaging: jest.fn(() => ({})),
    getToken: jest.fn(async () => 'fcm-token-1'),
    requestPermission: jest.fn(async () => 1),
    getInitialNotification: jest.fn(async () => null),
    setBackgroundMessageHandler: jest.fn(),
    onNotificationOpenedApp: jest.fn(subscribe('opened')),
    onMessage: jest.fn(subscribe('message')),
    onTokenRefresh: jest.fn(subscribe('token')),
    __emit: (kind: string, value: unknown) => listeners[kind].forEach((l) => l(value)),
    __listenerCount: (kind: string) => listeners[kind].size,
  };
});

// Appareil photo et retouche d'image (modules natifs Expo) : doubles contrôlables par les tests.
jest.mock('expo-image-picker', () => ({
  __esModule: true,
  getCameraPermissionsAsync: jest.fn(async () => ({ granted: false, canAskAgain: true, status: 'undetermined' })),
  requestCameraPermissionsAsync: jest.fn(async () => ({ granted: true, canAskAgain: true, status: 'granted' })),
  launchCameraAsync: jest.fn(async () => ({
    canceled: false,
    assets: [{ uri: 'file:///camera/photo.jpg', width: 4032, height: 3024 }],
  })),
}));

jest.mock('expo-image-manipulator', () => {
  const saveAsync = jest.fn(async () => ({ uri: 'file:///cache/compressed.jpg', width: 2048, height: 1536 }));
  const context: { resize: jest.Mock; renderAsync: jest.Mock } = {
    resize: jest.fn((): unknown => context),
    renderAsync: jest.fn(async () => ({ width: 2048, height: 1536, saveAsync })),
  };
  return {
    __esModule: true,
    SaveFormat: { JPEG: 'jpeg', PNG: 'png', WEBP: 'webp' },
    ImageManipulator: { manipulate: jest.fn(() => context) },
    __context: context,
    __saveAsync: saveAsync,
  };
});
