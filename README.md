# XtraLite for Twitch

<img src="app/src/main/ic_launcher-web.png" align="left" width="100"/>

XtraLite is a lightweight Twitch player and browser for Android, based on [Xtra](https://github.com/crackededed/Xtra).

</br>
</br>

## Differences from Xtra

- **No chat.** Live chat, chat replay, chat downloads, emotes and all chat settings are removed. Login only requests the `user:read:follows` scope.
- **Skip silence** for videos, clips and downloads (ExoPlayer only), as in [Twire](https://github.com/twireapp/Twire). Toggle it from the player menu; the choice is remembered.
- **Lighter on data and battery:**
  - In the background (screen off, app closed) playback switches to the audio-only stream instead of downloading video you can't see.
  - On mobile data, streams and videos start at 480p by default (Settings → Player → Default quality on mobile data).
  - On mobile data the player buffers at most 25 s ahead instead of 50 s (Settings → Buffer).
  - List thumbnails and avatars are requested at about a third of the previous file size.
  - Tabs on channel, game, following, search and saved pages load when you open them, not all at once.
  - "Use HTTP proxies" and "Use video swap" are marked in settings as using extra data during ads.
- **Cast to TV** with Google Cast (Chromecast, Android TV, Google TV). Use the cast button in the player controls; it can be hidden under Settings → Player buttons. Requires Google Play services and the phone on the same Wi-Fi as the TV.
  - Twitch doesn't let Cast receivers load its playlists directly, so XtraLite relays them from the phone over your local network (only H.264 qualities are offered to the TV). Live video segments go straight from Twitch to the TV; past broadcasts are relayed through the phone, so keep XtraLite running while casting them.
- **21 app icons** to choose from under Settings → Theme → App icon.

XtraLite installs as a separate app (`com.github.artanvrajolli.xtralite`) and can live next to Xtra.

## Download

Build with `./gradlew assembleRelease`; published APKs go on the [releases page](https://github.com/artanvrajolli/XtraLite/releases).

## License
XtraLite, like Xtra, is licensed under the [GNU Affero General Public License v3.0](LICENSE).
