# Xtra Lite for Twitch

<img src="app/src/main/ic_launcher-web.png" align="left" width="100"/>

Xtra Lite is a lightweight Twitch player and browser for Android, based on [Xtra](https://github.com/crackededed/Xtra).

</br>
</br>

## Differences from Xtra

- **No chat.** Live chat, chat replay, chat downloads, emotes and all chat settings are removed. Login only requests the `user:read:follows` scope.
- **Skip silence** for videos, clips and downloads (ExoPlayer only), as in [Twire](https://github.com/twireapp/Twire). Toggle it from the player menu; the choice is remembered.
- **Swipe for brightness and volume** in fullscreen, like YouTube ReVanced: swipe up or down on the left third for brightness and the right third for volume (the middle still drags the player down to minimize). Can be turned off in Settings → Player.
- **Lighter on data and battery:**
  - In the background (screen off, app closed) playback switches to the audio-only stream instead of downloading video you can't see.
  - On mobile data, streams and videos start at 480p by default (Settings → Player → Default quality on mobile data).
  - On mobile data the player buffers at most 25 s ahead instead of 50 s (Settings → Buffer).
  - List thumbnails and avatars are requested at about a third of the previous file size.
  - Tabs on channel, game, following, search and saved pages load when you open them, not all at once.
  - "Use HTTP proxies" and "Use video swap" are marked in settings as using extra data during ads.
- **Cast to TV** with Google Cast (Chromecast, Android TV, Google TV). Use the cast button in the player controls; it can be hidden under Settings → Player buttons. Requires Google Play services and the phone on the same Wi-Fi as the TV.
  - Twitch doesn't let Cast receivers load its playlists directly, so Xtra Lite relays them from the phone over your local network (only H.264 qualities are offered to the TV). Live video segments go straight from Twitch to the TV; past broadcasts are relayed through the phone, so keep Xtra Lite running while casting them.
  - While casting: full-screen remote (opens automatically), a "now casting" bar above the navigation bar, and media controls in the notification shade, quick settings and lock screen. Open another stream or video to send it to the TV instead, or use "Switch TV" in the remote to move the current video to another TV.
  - Progress of past broadcasts watched on the TV is saved, so opening them on the phone resumes where the TV left off. When casting stops, Xtra Lite offers to continue the video on the phone from the same point.
- **5 app icons** based on the Xtra logo (Lite, Classic, Purple, AMOLED, Minimal) under Settings → Theme → App icon.

Xtra Lite installs as a separate app (`com.github.artanvrajolli.xtralite`) and can live next to Xtra.

## Download

Download the APK from the [latest release](https://github.com/artanvrajolli/XtraLite/releases/latest), or build it with `./gradlew assembleRelease`.

## License
Xtra Lite, like Xtra, is licensed under the [GNU Affero General Public License v3.0](LICENSE).
