# Riya Assistant (Android app)

A tiny native app that makes Riya a real **digital assistant**: hold the home button / power button / corner swipe
(depends on your phone) and a Riya panel slides up. She listens, answers aloud, and keeps listening for a few turns.
No Chrome needed. The brain is still `riya-assistant` running in Termux on the same phone (127.0.0.1 only).

Status: written but NOT yet built or tested on a device. The Hey Riya part (new dependencies, always-on mic service) is the least proven. Expect to fix small things on the first build and run.

## 1. Build the APK in the cloud (no Android Studio needed)
GitHub builds it for you with the included workflow. From Termux:

    pkg install git unzip
    unzip riya-android.zip && cd riya-android
    git init -b main && git add . && git commit -m "Riya app"
    # create an EMPTY repo on github.com first (private is fine), then:
    git remote add origin https://github.com/<you>/riya-android.git
    git push -u origin main        # use your GitHub username + a personal access token as the password

Then on github.com open the repo -> Actions -> "Build APK" -> the finished run -> Artifacts -> `riya-assistant-apk`.
Download, unzip, tap `app-debug.apk` and allow "install unknown apps". (If the build fails, open the run log and send it to me.)

## 2. One-time setup on the phone
1. In Termux: `cat ~/riya-assistant/.riya_token | termux-clipboard-set`
2. For auto-start: `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties` then restart Termux.
   Copy the new `server-bg.sh` and `voice.py` from the updated riya-assistant folder if you haven't already.
3. Open the Riya app: **Paste token**, **Save**, **Grant microphone**, **Allow Termux start**.
4. **Set as default assistant** and choose Riya.
5. Hold home / power button (or tap **Test now**).

## Hands-free: just say "Hey Riya"
In the Riya app, in this order:
1. **Download wake-word model** (once, about 40 MB; the app fetches a small Indian-English Vosk model and unpacks it).
2. **Allow notifications** and **Don't restrict battery**.
3. **Start Hey Riya**. A small "Hey Riya" notification stays on while she waits (tap Stop in it to switch her off).
Then say her name with the screen off or locked. She answers "Ji Boss, boliye?" and you talk normally. Say "bye" or stay quiet to end.

- **Privacy:** waiting for her name runs OFFLINE on the phone (Vosk); nothing is sent anywhere. Once she wakes, Android's speech
  recognizer (Google) hears your request, and the text goes to your own Riya server.
- **Wake words** are editable in the app (default: riya, ria, rhea) because speech models spell names differently. If she wakes
  too often or never, change that list. If the model URL ever stops working, paste another Vosk small-model zip URL in the app.
- **Battery:** the mic is always on while she waits, so it costs battery. She is paused while the panel is open or you are on a call.
- **Reboot:** she restarts by herself after a reboot on older Android (like Android 10). Newer Android may refuse, so open the app and tap Start.
- The Termux server must be running; the app tries to start it through Termux when she wakes.

## How it works
- `RiyaInteractionService` / `RiyaSession`: Android calls these on the assistant gesture; they open `VoiceActivity`.
- `Conversation`: one chat, used by both the panel and "Hey Riya": Android speech recognizer -> `POST /ask` to Riya's server ->
  Android text-to-speech, for up to 12 turns.
- `VoiceActivity`: the bottom panel for the gesture. Closes on "bye", silence, or leaving the screen.
- `WakeService` + `ModelStore` + `BootReceiver`: the foreground "Hey Riya" listener, its model download, and restart after boot.
- `RiyaClient`: if the server is down it asks Termux to start it (`server-bg.sh`).
- Risky actions (calls, SMS, shell, commits) are still asked out loud through Termux, so you may see its listening popup.

## Known limits
- A single power-button tap can never be used by apps (it only turns the screen on/off). Only a long-press can open an assistant,
  and which gesture does that is up to your phone maker (Android 10: usually home long-press or a corner swipe; newer Pixel/Samsung: power long-press).
- Voice is the phone's own text-to-speech (no ElevenLabs yet).
- Debug-signed APK, for your own use only.
