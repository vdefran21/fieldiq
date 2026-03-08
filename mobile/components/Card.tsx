import { PropsWithChildren } from 'react';
import { StyleSheet, View } from 'react-native';

/**
 * Shared card container for the phase-1 mobile screens.
 *
 * @param children Card body content.
 */
export function Card({ children }: PropsWithChildren) {
  return <View style={styles.card}>{children}</View>;
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: '#fffdf8',
    borderRadius: 20,
    padding: 18,
    gap: 12,
    borderWidth: 1,
    borderColor: '#ded7c8',
  },
});
