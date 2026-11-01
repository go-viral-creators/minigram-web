package com.silu.minigram;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;

public class SketchApplication extends Application {

    private static final String TAG = "MinigramFCM";

    private static Context mApplicationContext;

    /** True once Firebase has been successfully initialized this process. */
    private static volatile boolean firebaseReady = false;

    public static Context getContext() {
        return mApplicationContext;
    }

    /** Other classes (FcmTokenManager, Activities) can check this before
     *  touching Firebase, instead of finding out via a crash. */
    public static boolean isFirebaseReady() {
        return firebaseReady;
    }

    @Override
    public void onCreate() {
        mApplicationContext = getApplicationContext();

        // ═══════════════════════════════════════════════════════════════════
        // STEP 1 — crash handler SABSE PEHLE set karo.
        //
        // Pehle ye Firebase init ke baad set hota tha. AndroidManifest se
        // FirebaseInitProvider hata diya gaya hai (wo Application.onCreate()
        // se PEHLE chalta tha aur is handler ke set hone se pehle hi crash
        // kar sakta tha). Ab Firebase init bhi isi method ke andar, is
        // handler ke baad hota hai — matlab Firebase se related koi bhi
        // crash ab is handler se guaranteed pakda jayega.
        // ═══════════════════════════════════════════════════════════════════
        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable throwable) {
                        Intent intent = new Intent(getApplicationContext(), DebugActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.putExtra("error", Log.getStackTraceString(throwable));
                        startActivity(intent);
                        SketchLogger.broadcastLog(Log.getStackTraceString(throwable));
                        Process.killProcess(Process.myPid());
                        System.exit(1);
                    }
                });
        SketchLogger.startLogging();
        AppLock.install(this);
        Appearance.applyTheme(this);

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2 — Firebase ko khud, defensively initialize karo.
        //
        // Pehle ye kaam FirebaseInitProvider automatically, Application se
        // PEHLE, bina kisi try/catch ke karta tha — isliye koi bhi missing
        // dependency class seedha app ko crash kar deti thi, bina kisi log
        // ke, bina DebugActivity dikhe.
        //
        // Ab ye try/catch(Throwable) ke andar hai:
        //   • Sab sahi → firebaseReady = true, app bilkul pehle jaisa chalta hai.
        //   • Kuch missing/toota → poora stack trace log hota hai (Log.e +
        //     SketchLogger, jo tumhare existing crash-log pipeline mein jaata
        //     hai), aur app CRASH NAHI hota — Firebase-dependent features
        //     (login, chat, notifications) uss process ke liye disabled reh
        //     jaate hain, par app khulta hai aur baaki UI/logs accessible
        //     rehte hain.
        //
        // NoClassDefFoundError ek Error hai (Exception nahi), isliye Throwable
        // pakadna zaroori hai — sirf catch (Exception e) ise miss kar deta.
        // ═══════════════════════════════════════════════════════════════════
        try {
            FirebaseApp.initializeApp(this);
            AppCheckInstaller.install();

            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
            FirebaseDatabase.getInstance().setPersistenceCacheSizeBytes(70 * 1024 * 1024);

            firebaseReady = true;
            Log.d(TAG, "Firebase initialized OK");

        } catch (Throwable t) {
            firebaseReady = false;
            String trace = Log.getStackTraceString(t);
            Log.e(TAG, "Firebase init FAILED — app continuing without Firebase. " + trace);
            SketchLogger.broadcastLog("FIREBASE INIT FAILED:\n" + trace);

            // NOTE: agar ye branch chal rahi hai, to iska matlab
            // ComponentDiscoveryService mein declared kisi registrar ki
            // dependency jar abhi bhi missing/mismatched hai. `adb logcat -s
            // MinigramFCM` chalao — "Firebase init FAILED" ke turant baad
            // wali "Caused by:" line mein exact missing class ka naam hoga.
        }

        super.onCreate();
    }
}
