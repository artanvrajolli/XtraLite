# XtraLite for Twitch

<img src="https://github.com/AndreyAsadchy/Xtra/blob/197ba90cac879abd2a5645393ce361847f12fa0b/app/src/main/ic_launcher-web.png" align="left" width="100"/>

XtraLite is a lightweight Twitch player and browser for Android, based on [Xtra](https://github.com/crackededed/Xtra).

</br>
</br>

## Differences from Xtra

- **No chat.** Live chat, chat replay, chat downloads, emotes and all chat settings are removed. Login only requests the `user:read:follows` scope.
- **Skip silence** for videos, clips and downloads (ExoPlayer only), as in [Twire](https://github.com/twireapp/Twire). Toggle it from the player menu; the choice is remembered.
- **Cast to TV** with Google Cast (Chromecast, Android TV, Google TV). Use the cast button in the player controls; it can be hidden under Settings → Player buttons. Requires Google Play services.

XtraLite installs as a separate app (`com.github.artanvrajolli.xtralite`) and can live next to Xtra.

## Download

Build with `./gradlew assembleRelease`; published APKs go on the [releases page](https://github.com/artanvrajolli/XtraLite/releases).

## License
XtraLite, like Xtra, is licensed under the [GNU Affero General Public License v3.0](LICENSE).
