import * as SecureStore from 'expo-secure-store';

/**
 * Persisted authentication session for the mobile app.
 */
export interface StoredSession {
  accessToken: string;
  refreshToken: string;
}

const SESSION_KEY = 'fieldiq.session';

/**
 * Reads the current stored session from secure storage.
 *
 * @returns Stored access/refresh pair, or null when the user is signed out.
 */
export async function getStoredSession(): Promise<StoredSession | null> {
  const raw = await SecureStore.getItemAsync(SESSION_KEY);
  return raw ? (JSON.parse(raw) as StoredSession) : null;
}

/**
 * Persists the latest access and refresh tokens.
 *
 * @param session Session payload to store.
 */
export async function saveSession(session: StoredSession): Promise<void> {
  await SecureStore.setItemAsync(SESSION_KEY, JSON.stringify(session));
}

/**
 * Clears the locally stored session.
 */
export async function clearSession(): Promise<void> {
  await SecureStore.deleteItemAsync(SESSION_KEY);
}
