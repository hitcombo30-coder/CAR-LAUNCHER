# Noor Drive (Android car launcher)

Islamic luxury car home screen: map (left), clock/weather/prayer, speed, music (YouTube / YT Music / Radio), apps.
It installs as a Home app, so the Home button always comes back here and Back never exits.

## Build the APK with GitHub (no PC needed)
1. Upload everything in this folder to a new GitHub repo (keep the folder structure, including `.github/workflows/build.yml`).
2. Open the **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push to `main`).
3. When it finishes, open the run and download the **NoorDrive-APK** artifact (a zip with `app-debug.apk`).
4. Install the APK on the head unit (allow "install unknown apps").
5. Press Home: Android asks which Home app to use. Pick **Noor Drive** and choose **Always**.

## Change the look or features
The whole UI is one file: `app/src/main/assets/index.html`. Edit it on GitHub, commit, and Actions builds a new APK.
To flip the map to the right side again, remove `flex-direction:row-reverse;` from the `#grid` CSS rule.

## Notes
- Debug APK is signed with a debug key. Fine for your own car.
- Maps: OpenStreetMap tiles. Routing: OSRM demo server. Both are for light personal use.
