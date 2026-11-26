import { getMessaging, setBackgroundMessageHandler } from '@react-native-firebase/messaging';
import { registerRootComponent } from 'expo';

import App from './App';

// registerRootComponent calls AppRegistry.registerComponent('main', () => App);
// It also ensures that whether you load the app in Expo Go or in a native build,
// the environment is set up appropriately
registerRootComponent(App);

// Application fermée / en arrière-plan : l'OS affiche lui-même les notifications de rappel (bloc
// « notification » du message FCM). Rien à faire ici ; le gestionnaire évite l'avertissement de RNFB.
setBackgroundMessageHandler(getMessaging(), async () => undefined);
