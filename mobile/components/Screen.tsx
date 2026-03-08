import { PropsWithChildren } from 'react';
import { SafeAreaView, ScrollView, StyleSheet, View, Text } from 'react-native';

/**
 * Shared mobile screen scaffold with FieldIQ's phase-1 visual language.
 *
 * @param title Primary screen title.
 * @param subtitle Optional supporting copy under the title.
 * @param children Screen body content.
 */
export function Screen({
  title,
  subtitle,
  children,
}: PropsWithChildren<{ title: string; subtitle?: string }>) {
  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.hero}>
          <Text style={styles.kicker}>FieldIQ</Text>
          <Text style={styles.title}>{title}</Text>
          {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
        </View>
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f5efe3',
  },
  content: {
    padding: 20,
    gap: 18,
  },
  hero: {
    backgroundColor: '#14281d',
    borderRadius: 24,
    padding: 20,
    gap: 8,
  },
  kicker: {
    color: '#f6f4ee',
    fontSize: 12,
    textTransform: 'uppercase',
    letterSpacing: 2,
  },
  title: {
    color: '#f6f4ee',
    fontSize: 30,
    fontWeight: '700',
  },
  subtitle: {
    color: '#d8ddcf',
    fontSize: 15,
    lineHeight: 22,
  },
});
