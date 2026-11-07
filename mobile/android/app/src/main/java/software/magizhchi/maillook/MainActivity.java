package software.magizhchi.maillook;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/**
 * Magizhchi MailLook for Android: the web app (https://maillook.magizhchi.software) in a native window.
 * Here: the two alert channels with MailLook's own sounds, and the small MailLookNative plugin.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MailLookNativePlugin.class);
        super.onCreate(savedInstanceState);
        createChannels();
    }

    /**
     * "New mail" rings with the chime; "Reminders" (snooze, no reply, the morning briefing, a mail that didn't
     * send) with the double chime, at high importance so they show on top. People can change either in the
     * phone's settings. The server names the channel in each alert (FcmService).
     */
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel mail = new NotificationChannel("mail", "New mail", NotificationManager.IMPORTANCE_DEFAULT);
        mail.setDescription("Mail that needs you (or all new mail, if you chose that in MailLook)");
        mail.setSound(sound("chime"), audio);
        mail.enableLights(true);
        mail.setLightColor(0xFF0D6A75);
        nm.createNotificationChannel(mail);

        NotificationChannel reminders = new NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("Snoozed mail coming back, nobody replied, your morning briefing, and mail that didn't send");
        reminders.setSound(sound("reminder"), audio);
        reminders.enableVibration(true);
        reminders.setVibrationPattern(new long[]{0, 250, 150, 250});
        nm.createNotificationChannel(reminders);
    }

    private Uri sound(String name) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getPackageName() + "/raw/" + name);
    }
}
