import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { api } from '../../services/api';

/**
 * OTP login screen for the phase-1 mobile shell.
 */
export default function LoginScreen() {
  const [value, setValue] = useState('+15551234567');
  const [otp, setOtp] = useState('123456');
  const [requested, setRequested] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Normalizes common US phone input into E.164 for the backend OTP API.
   *
   * Rules:
   * - `4107010177` -> `+14107010177`
   * - `14107010177` -> `+14107010177`
   * - `+14107010177` stays unchanged
   * - any other shape is passed through after stripping formatting so the backend
   *   can still enforce its canonical validation rules.
   *
   * @param rawValue Phone number entered by the user.
   * @returns Backend-ready identifier for the SMS OTP endpoints.
   */
  function normalizePhoneNumber(rawValue: string): string {
    const trimmed = rawValue.trim();
    const hasPlus = trimmed.startsWith('+');
    const digits = trimmed.replace(/\D/g, '');

    if (hasPlus) {
      return `+${digits}`;
    }
    if (digits.length === 10) {
      return `+1${digits}`;
    }
    if (digits.length === 11 && digits.startsWith('1')) {
      return `+${digits}`;
    }

    return digits;
  }

  async function requestOtp() {
    setError(null);
    const normalizedValue = normalizePhoneNumber(value);
    try {
      await api.auth.requestOtp({ channel: 'sms', value: normalizedValue });
      setValue(normalizedValue);
      setRequested(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to request OTP');
    }
  }

  async function verifyOtp() {
    setError(null);
    const normalizedValue = normalizePhoneNumber(value);
    try {
      await api.auth.verifyOtp({ channel: 'sms', value: normalizedValue, otp });
      setValue(normalizedValue);
      router.replace('/(app)');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to verify OTP');
    }
  }

  return (
    <Screen
      title="Coach login"
      subtitle="Passwordless entry for managers. Request an OTP, verify it, and jump straight into the schedule."
    >
      <Card>
        <Text style={styles.label}>Phone number</Text>
        <TextInput
          style={styles.input}
          value={value}
          onChangeText={setValue}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="phone-pad"
          textContentType="telephoneNumber"
        />
        <Text style={styles.helper}>US numbers auto-normalize to +1 when you request the OTP.</Text>
        <Pressable style={styles.button} onPress={requestOtp}>
          <Text style={styles.buttonText}>Request OTP</Text>
        </Pressable>
      </Card>

      <Card>
        <Text style={styles.label}>Verification code</Text>
        <TextInput style={styles.input} value={otp} onChangeText={setOtp} keyboardType="number-pad" />
        <Pressable style={[styles.button, !requested && styles.buttonDisabled]} onPress={verifyOtp} disabled={!requested}>
          <Text style={styles.buttonText}>Verify and continue</Text>
        </Pressable>
        {error ? <Text style={styles.error}>{error}</Text> : null}
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: '#2b2d2f',
  },
  input: {
    borderWidth: 1,
    borderColor: '#c8c1b1',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    backgroundColor: '#f9f4eb',
  },
  button: {
    backgroundColor: '#d95d39',
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
  },
  buttonDisabled: {
    opacity: 0.45,
  },
  buttonText: {
    color: '#fffaf0',
    fontWeight: '700',
    fontSize: 15,
  },
  error: {
    color: '#b42318',
    fontSize: 14,
  },
  helper: {
    color: '#6e665a',
    fontSize: 13,
    lineHeight: 18,
  },
});
