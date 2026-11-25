package app.seb3thehacker.gearslip.shizuku;

import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

// The part of Gearslip that Shizuku runs as the shell user, with the same powers as adb.
interface IShellService {
    // Shizuku's own transaction code for ending the service process.
    void destroy() = 16777114;

    // Runs a shell command and returns its exit code. Only Gearslip's own fixed commands.
    int run(String command) = 1;

    // A display drawn into [surface] that any app can be started on. Returns its id, or -1.
    int createDisplay(in Surface surface, int width, int height, int densityDpi) = 2;

    // Starts [component] (package/activity) on the display. Returns am's exit code.
    int launch(String component, int displayId) = 3;

    // The package whose activity is on top of the display, or null when it's empty.
    String topPackage(int displayId) = 4;

    oneway void injectMotion(in MotionEvent event, int displayId) = 5;

    oneway void injectKey(in KeyEvent event, int displayId) = 6;

    void releaseDisplay(int displayId) = 7;

    // Records everything the phone plays, as adb can, and hands back the read end of a pipe of
    // 16-bit PCM. Null when the recording can't start.
    ParcelFileDescriptor startAudio(int sampleRate, int channels) = 8;

    void stopAudio() = 9;
}
