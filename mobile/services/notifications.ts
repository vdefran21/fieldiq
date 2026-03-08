import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';

/**
 * Result returned when the app attempts Expo push registration.
 *
 * The mobile shell treats missing Expo project configuration and simulator usage as
 * non-fatal states so the rest of the app can keep working during local development.
 */
export type PushRegistrationResult =
  | { status: 'registered'; token: string }
  | { status: 'not_configured' | 'unavailable' | 'denied'; reason: string };

/**
 * Returns the configured Expo project ID when one is available.
 *
 * Expo Go on physical devices needs this value before `getExpoPushTokenAsync()`
 * can issue a token. Local MVP development should still work when it is absent.
 *
 * @returns Expo project ID, or null when push is not configured for the build.
 */
export function getExpoProjectId(): string | null {
  const extra = Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined;
  const projectId =
    Constants.easConfig?.projectId ?? extra?.eas?.projectId ?? process.env.EXPO_PUBLIC_EAS_PROJECT_ID;

  return projectId?.trim() ? projectId.trim() : null;
}

/**
 * Indicates whether the current Expo build is configured for push token issuance.
 *
 * @returns True when an Expo project ID is available, otherwise false.
 */
export function isPushConfigured(): boolean {
  return getExpoProjectId() !== null;
}

/**
 * Registers for push notifications and returns either a token or a readable non-fatal state.
 *
 * The backend already exposes `POST /users/me/devices`, so the mobile shell can
 * record a token as soon as the user is authenticated when Expo push is configured.
 *
 * @returns Structured registration result for settings/bootstrap flows.
 */
export async function registerForPushNotifications(): Promise<PushRegistrationResult> {
  if (!Device.isDevice) {
    return {
      status: 'unavailable',
      reason: 'Notifications are only available on a physical device.',
    };
  }

  const projectId = getExpoProjectId();
  if (!projectId) {
    return {
      status: 'not_configured',
      reason: 'Push not configured for this Expo build yet.',
    };
  }

  const permission = await Notifications.requestPermissionsAsync();
  if (permission.status !== 'granted') {
    return {
      status: 'denied',
      reason: 'Notification permission was not granted.',
    };
  }

  const token = await Notifications.getExpoPushTokenAsync({ projectId });
  return { status: 'registered', token: token.data };
}
