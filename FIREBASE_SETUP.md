# Firebase setup (required before remote monitoring works)

1. In the connected Firebase project, register Android package `com.aistudio.parentalcontrol.whntoq`.
2. Download `google-services.json` into `app/google-services.json` (never commit production secrets).
3. Enable Authentication > Anonymous for development. Replace with verified parent accounts before release.
4. Create Cloud Firestore in production mode.
5. From this project directory run `firebase deploy --only firestore:rules,firestore:indexes`.
6. In `functions/`, run `npm install`, then `npm run build`, then deploy functions.
7. After the existing OTP is verified on each device, call `completePairing` with the correct local role so Firestore stores parentUid/childUid. Rules intentionally deny untrusted direct pairing writes.
8. Enable Play Integrity and Firebase App Check before production.
9. SMS: `READ_SMS` is Play-restricted. Use this only for private/managed distribution with explicit consent, or implement the Android default-SMS role before Play release.
10. Live location uses a visible foreground notification and starts only from a visible child screen. Android may require the user to grant background location separately in App Settings.

Current source keeps the original WebRTC transport. Firebase monitoring does not supply TURN. Configure a managed TURN/LiveKit service separately for worldwide screen sharing.
