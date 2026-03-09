import crypto from 'crypto';
import { config } from '../../config';

/**
 * Encrypts a token using the same AES-256-GCM format as the backend's
 * TokenEncryptionConverter.
 *
 * Used in test setup to insert realistic encrypted tokens into
 * `calendar_integrations`. The agent's `decryptToken()` must be able
 * to decrypt these — validating cross-layer encryption compatibility.
 *
 * **Format:** `Base64(IV[12 bytes] + ciphertext + authTag[16 bytes])`
 *
 * Uses the encryption key from the agent's config (which must match
 * the backend's `fieldiq.encryption.token-key`).
 *
 * @param plaintext The token value to encrypt.
 * @returns Base64-encoded ciphertext in the backend-compatible format.
 *
 * @see backend/src/main/kotlin/com/fieldiq/security/TokenEncryptionConverter.kt
 * @see agent/src/encryption.ts (decryptToken)
 */
export function encryptLikeBackend(plaintext: string): string {
  const keyBytes = deriveKeyBytes(config.encryption.tokenKey);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', keyBytes, iv);
  const encrypted = Buffer.concat([
    cipher.update(plaintext, 'utf8'),
    cipher.final(),
  ]);
  const authTag = cipher.getAuthTag();
  // Backend format: IV + ciphertext + authTag
  const combined = Buffer.concat([iv, encrypted, authTag]);
  return combined.toString('base64');
}

/**
 * Derives a 32-byte AES key from a config string.
 *
 * Matches the production logic in `agent/src/encryption.ts`:
 * - Keys >= 32 bytes are truncated to 32
 * - Keys < 32 bytes are zero-padded to 32
 *
 * Without this, a short TOKEN_ENCRYPTION_KEY would produce a different
 * key than production, causing decryption failures in integration tests.
 *
 * @param keyString Raw configured token-encryption secret.
 * @returns AES-256 key material derived with production-compatible rules.
 */
function deriveKeyBytes(keyString: string): Buffer {
  const keyBytes = Buffer.from(keyString, 'utf8');
  if (keyBytes.length >= 32) {
    return keyBytes.subarray(0, 32);
  }
  const padded = Buffer.alloc(32);
  keyBytes.copy(padded);
  return padded;
}
