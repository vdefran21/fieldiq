const { networkInterfaces } = require('node:os');
const { spawn } = require('node:child_process');

/**
 * Returns the first non-internal IPv4 address on the machine.
 *
 * This is used to generate `EXPO_PUBLIC_API_URL` values that work from a physical
 * phone on the same LAN. The backend still runs on port 8080 unless overridden.
 *
 * @returns {string | null} Detected LAN IPv4 address, or null if none is found.
 */
function getLanIp() {
  const interfaces = networkInterfaces();

  for (const addresses of Object.values(interfaces)) {
    if (!addresses) continue;
    for (const address of addresses) {
      if (address.family === 'IPv4' && !address.internal) {
        return address.address;
      }
    }
  }

  return null;
}

const mode = process.argv[2] || 'start';
const lanIp = getLanIp();

if (!lanIp) {
  console.error('Unable to determine a LAN IPv4 address for this Mac.');
  process.exit(1);
}

const env = {
  ...process.env,
  EXPO_PUBLIC_API_URL: process.env.EXPO_PUBLIC_API_URL || `http://${lanIp}:8080`,
};

const args = mode === 'ios' ? ['expo', 'start', '--ios'] : ['expo', 'start'];

console.log(`Using EXPO_PUBLIC_API_URL=${env.EXPO_PUBLIC_API_URL}`);

const child = spawn('npx', args, {
  stdio: 'inherit',
  env,
  shell: true,
});

child.on('exit', (code) => {
  process.exit(code ?? 0);
});
