package software.magizhchi.maillook;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * What the MailLook web app asks the phone app, as window.Capacitor.Plugins.MailLookNative:
 *   info()                → { version, pushReady, notificationsOn }
 *   openAlertSettings()   → the phone's notification settings for MailLook
 */
@CapacitorPlugin(name = "MailLookNative")
public class MailLookNativePlugin extends Plugin {

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        String version = "";
        try {
            version = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // unknown
        }
        r.put("version", version);
        // Firebase is set up only when the build had a google-services.json (it adds google_app_id). Without
        // it, asking Firebase for a token would crash the app, so the web app doesn't ask.
        r.put("pushReady", getContext().getResources().getIdentifier("google_app_id", "string", getContext().getPackageName()) != 0);
        r.put("notificationsOn", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        call.resolve(r);
    }

    @PluginMethod
    public void openAlertSettings(PluginCall call) {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
