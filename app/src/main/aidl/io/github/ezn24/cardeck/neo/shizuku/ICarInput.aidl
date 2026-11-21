// Shizuku UserService interface: runs in a resident shell process and injects touch events with millisecond latency.
// Compared with spawning `input` per event (~200-400ms), injectInputEvent talks to the system service directly.
package io.github.ezn24.cardeck.neo.shizuku;

import android.view.Surface;

import android.os.ParcelFileDescriptor;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // Continuous-drag primitive: one DOWN shared by the whole swipe, MOVE events injected one by one (real timestamps),
    // so the app-side velocity tracker computes correct fling inertia. Single pointer only.
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // Key injection (iDrive knob -> DPAD/confirm keys): same injectInputEvent path as touch;
    // KeyEvent carries targetDisplayId and goes straight to the virtual display, ms-level, never touches the phone display.
    // Returns false on failure (caller falls back to `input -d <id> keyevent <code>`).
    boolean keyEvent(int displayId, int keyCode);

    // Trusted virtual display (shell privilege): new tasks of third-party apps can land on it,
    // which fixes "tasks started from inside an app get redirected to the phone display" (AOSP security policy).
    // trusted=false: do not declare FLAG_TRUSTED, so the system cannot place third-party tasks on this display
    // (the dock display only hosts our own DockActivity; dropping trust stops other apps' tasks from escaping onto it).
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // Resize a virtual display dynamically (creator only): the system dispatches the config change to the apps on it,
    // which relayout themselves. The map display's split <-> fullscreen switch uses this (task stays, state is kept).
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    // Hardware power of the physical main display (SurfaceControl.setDisplayPowerMode): false = power off (real screen-off),
    // true = power back on. The system logically stays awake (no lock, no Doze) and virtual displays keep rendering.
    // Same principle as scrcpy -S/--turn-screen-off; returns false on failure (ROM denied / display token unavailable).
    boolean setPhysicalScreenPower(boolean on);

    boolean ping();
    String diag();

    // True fake screen-off: power the built-in panel off/on at SurfaceControl level (POWER_MODE_OFF/NORMAL).
    // The system stays interactive (no sleep, no keyguard, virtual displays unaffected) while the panel is really off.
    // Needs ACCESS_SURFACE_FLINGER, which shell already holds (Honor Magic7 Pro measured granted=true).
    // Reflection chain SurfaceControl -> DisplayControl (token entry point migrated in newer systems), falling back level by level.
    boolean setScreenPower(boolean on);

    // PID of the service process: lets the app exclude itself when cleaning stale UserService processes other than the bound one
    // (virtual displays held by an old process cannot be released cross-process; killing the process is the only way, see CarInputClient).
    int pid();
    // System output only (REMOTE_SUBMIX); does not capture the microphone.
    ParcelFileDescriptor startAudioCapture(int sampleRate, int channels);
    void stopAudioCapture();

    // Whether a voice assistant is listening: checks whether the RECORD_AUDIO and RECORD_AUDIO_HOTWORD AppOps of the
    // Google app / Gemini are active (AppOpsManager.isOpActive; shell holds WATCH_APPOPS).
    // 2026-11-15 S24: the recording-config list does not show Gemini's HOTWORD recording, but these two ops are reliable.
    boolean isAssistantListening();

    // Car microphone injection (2026-11-17, see docs/MIC-RESEARCH.md): registers an AudioPolicy recording injection
    // (loopback + capture preset rule, needs MODIFY_AUDIO_ROUTING, which shell holds) and returns the pipe write end;
    // the app writes s16le mono PCM (sampleRate) into it, and meanwhile other apps' MIC / speech recognition / call recording
    // capture this audio. Pipe EOF (app died) or stopMicInjection unregisters it and restores the real microphone.
    ParcelFileDescriptor startMicInjection(int sampleRate);
    void stopMicInjection();

    // Apps currently recording (RECORD_AUDIO op active), "uid:package" comma-separated.
    // Maintained with AppOpsManager.startWatchingActive (shell holds WATCH_APPOPS); watching starts on the first call.
    String activeMicApps();

    // Remove a task completely (including the phone's Recents entry): IActivityTaskManager.removeTask, shell holds
    // REMOVE_TASKS. `am stack remove` only deletes the task and the Recents entry stays on the phone (2026-11-18 measured).
    // Returns the number of tasks removed.
    int removeTasks(in int[] taskIds);

    // Leave the virtual display and its tasks alive, with no compositor surface while disconnected.
    boolean detachDisplaySurface(int displayId);

    // Car call card fallback when the call notification has no CallStyle actions: 1 = answer the ringing call,
    // 2 = end the call, through ITelecomService (shell holds ANSWER_PHONE_CALLS / MODIFY_PHONE_STATE). Returns 1 on success.
    int callControl(int action);

    // VoIP far-end voice for the car: loopback capture of USAGE_VOICE_COMMUNICATION playback (taken off the phone
    // speaker), 16-bit mono PCM at sampleRate through the returned pipe. Closing the pipe stops it.
    ParcelFileDescriptor startCallCapture(int sampleRate);
    void stopCallCapture();

    // Apps (uid >= 10000) playing USAGE_NOTIFICATION_RINGTONE right now, comma-separated packages. WeChat rings without
    // any call notification while its screen is up (2026-11-22), so its ringtone is the only sign of the call. Shell
    // holds MODIFY_AUDIO_ROUTING, so AudioService reports real client uids to it.
    String ringingApps();
}
