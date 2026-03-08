import { Redirect } from 'expo-router';

/**
 * Root route redirect for the mobile shell.
 */
export default function IndexScreen() {
  return <Redirect href="/(auth)/login" />;
}
