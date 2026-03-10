# FieldIQ Mobile

React Native / Expo mobile client for FieldIQ Phase 1. This app is iOS-first and is currently focused on the manager workflow:

- passwordless OTP login
- team onboarding, schedule, and roster views
- device registration for push notifications
- negotiation status viewing over WebSocket

The mobile app talks directly to the Kotlin backend and relies on the shared API types in `shared/types/`.

## Current State

The mobile workspace is on Expo SDK 54 and is intended to work with current Expo Go on iOS.

Implemented today:
- Expo Router app shell
- login flow using `POST /auth/request-otp` and `POST /auth/verify-otp`
- SecureStore session persistence
- schedule, team, settings, and negotiation route scaffolding
- recurring availability setup for the current manager and team
- WebSocket subscription helper for negotiation updates
- create-team flow from authenticated empty states
- device registration using `POST /users/me/devices`

Not fully complete yet:
- polished negotiation approval UX
- calendar connect UI
- rich push delivery via Expo service and EAS project configuration
- full end-to-end validation against live backend instances

## Demo prerequisite: availability

Cross-team negotiation only works when each instance has positive availability data.
For local demos, seed deterministic recurring availability before testing the mobile
negotiation flow:

```bash
node ../scripts/seed-demo-availability.mjs --reset
```

Inside the app, managers can also open the hidden `Availability` flow from the
schedule or team screens to create recurring weekly baseline availability manually.
The seed script validates that the two demo baselines still expose a mutual slot
before you start the cross-instance negotiation proof-of-concept.

If you prefer to orchestrate the demo from the repo root, `dev.sh` can now launch
the mobile demo Metro servers for you:

```bash
cd /Users/thedaego/fieldiq
./dev.sh start-mobile-demo
```

That command starts two detached Expo Metro processes:
- Metro A on port `8082` with `EXPO_PUBLIC_API_URL=http://<demo-host>:8080`
- Metro B on port `8083` with `EXPO_PUBLIC_API_URL=http://<demo-host>:8081`

For the full detached demo stack, use:

```bash
cd /Users/thedaego/fieldiq
./dev.sh demo-up
```

## Prerequisites

- Node.js 20+
- Xcode/iOS Simulator if you want simulator-based testing
- Expo Go on a physical iPhone if you want device testing
- Backend instance A running on port `8080`
- Backend instance B running on port `8081` when testing cross-instance negotiation
- Local infra running if the backend/agent need it

Typical backend setup from repo root:

```bash
docker compose up -d
cd backend
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun
```

In a second terminal:

```bash
cd backend
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
```

## Install

From `mobile/`:

```bash
npm install
```

## Running

### `npm start`

```bash
npm start
```

This runs:

```bash
expo start
```

What it does:
- starts Metro
- shows the Expo QR code
- lets you open the app in Expo Go, simulator, or web
- does **not** automatically set a device-safe backend URL

Use this when:
- you are testing in an iOS simulator
- you are manually exporting `EXPO_PUBLIC_API_URL`
- you only need Metro up and want to choose how to connect

Important:
- if `EXPO_PUBLIC_API_URL` is not set, the app defaults to `http://localhost:8080`
- that works for an iOS simulator on the same Mac
- that does **not** work on a physical phone, because `localhost` on the phone is the phone itself

### `npm run ios`

```bash
npm run ios
```

This runs:

```bash
expo start --ios
```

What it does:
- starts Metro
- immediately attempts to open the iOS simulator
- is mainly for simulator-based development on the Mac

Use this when:
- you want the fastest simulator workflow
- you are not testing through Expo Go on a physical iPhone

Difference from `npm start`:
- `npm start` starts Metro and waits for you to choose a target
- `npm run ios` starts Metro and tries to boot/open the iOS simulator automatically

## Running On A Physical Phone

For a physical iPhone, the app must call your Mac over the LAN, not `localhost`.

### Recommended scripts

```bash
npm run start:lan
```

or

```bash
npm run ios:lan
```

These scripts:
- detect the Mac's first non-internal IPv4 address
- set `EXPO_PUBLIC_API_URL=http://<lan-ip>:8080`
- launch Expo with that environment variable

Example output:

```bash
Using EXPO_PUBLIC_API_URL=http://192.168.1.42:8080
```

This is the safest default for testing from Expo Go on a real device.

### Manual override

If you want to force a specific backend URL:

```bash
EXPO_PUBLIC_API_URL=http://192.168.1.42:8080 npm start
```

or

```bash
EXPO_PUBLIC_API_URL=http://192.168.1.42:8080 npm run ios
```

The LAN scripts respect an existing `EXPO_PUBLIC_API_URL`, so you can still override them:

```bash
EXPO_PUBLIC_API_URL=http://10.0.0.15:8080 npm run start:lan
```

## How LAN IP Detection Works

The helper script is:

[`scripts/run-expo-with-lan.js`](/Users/thedaego/fieldiq/mobile/scripts/run-expo-with-lan.js)

It uses Node's `os.networkInterfaces()` to:
- enumerate the Mac's network interfaces
- find the first non-internal IPv4 address
- inject `EXPO_PUBLIC_API_URL=http://<detected-ip>:8080`

This avoids shell-specific commands like `ipconfig getifaddr en0` in `package.json`.

Why this approach is better:
- works from npm scripts directly
- easier to document and debug
- avoids zsh/bash quoting issues
- avoids hardcoding `en0` vs `en1`

## Environment Variables

### `EXPO_PUBLIC_API_URL`

Backend base URL used by `services/api.ts`.

Default:

```text
http://localhost:8080
```

Examples:

```bash
EXPO_PUBLIC_API_URL=http://localhost:8080 npm run ios
EXPO_PUBLIC_API_URL=http://192.168.1.42:8080 npm start
```

### `EXPO_PUBLIC_EAS_PROJECT_ID`

Optional Expo project ID used when requesting a physical-device push token from Expo Go.

If this is missing:
- the app still loads and works
- settings will report that push is not configured for the current build
- automatic device registration is skipped instead of crashing

Example:

```bash
EXPO_PUBLIC_EAS_PROJECT_ID=your-expo-project-id npm run start:lan
```

## Common Issues

### Expo Go says the project is incompatible

Make sure the mobile workspace stays on the current supported Expo SDK for Expo Go on iOS.

Check:

```bash
npx expo install --check
```

### Metro wants port `8081`

That usually means your backend instance B is already using `8081`. Expo can use another port; this is fine.

### The app opens but API calls fail on a phone

Most likely cause:
- `EXPO_PUBLIC_API_URL` is still pointing at `localhost`

Fix:
- use `npm run start:lan`
- or set `EXPO_PUBLIC_API_URL` to your Mac's LAN IP manually

### The app works in simulator but not on phone

That usually means:
- simulator can reach `localhost`
- phone cannot

Use a LAN URL and make sure the phone and Mac are on the same network.

### Login works but live negotiation updates do not

Check:
- backend is running with the WebSocket endpoint available
- the JWT-authenticated negotiation session belongs to the logged-in user
- the phone can reach the backend over the same LAN URL used for REST

### Settings shows "Push not configured for this Expo build"

That means Expo can run the app, but `getExpoPushTokenAsync()` does not have a project ID.

Fix:
- set `EXPO_PUBLIC_EAS_PROJECT_ID`
- or add `expo.extra.eas.projectId` in `app.json`

Until then, push registration is treated as optional and non-fatal in development.

## Useful Commands

```bash
npm install
npm start
npm run start:lan
npm run ios
npm run ios:lan
npm run lint
npx expo install --check
```
