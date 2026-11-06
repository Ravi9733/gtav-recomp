package com.gtavrecomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * First screen of the public APK, which holds no game code and no game files. The player brings
 * two things, both made from their own copy of the game:
 *  - gtav-android-pack.zip from "Build for Android" in the PC launcher: their recompiled game
 *    library (libgtav_game.so), the shaders for this GPU and the known-good file hashes;
 *  - their discs: Disc 1 and Disc 2 (ISO images) and Title Update 27 (the TU file).
 * The pack goes to the app's private storage (the game library must be there: shared storage is
 * mounted noexec); the game files are copied from the discs and checked file by file into the
 * app's own folder (libgtav_setup.so, the same installer as on the PC and iPhone). When both are in
 * place, this screen starts the game directly.
 */
public class SetupActivity extends Activity {
    // Offline, bundled launcher artwork (original fan-made GTA-style scene).
    private static final String LAUNCHER_ART_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDADknKzIrJDkyLjJAPTlEVo9dVk9PVq99hGiPz7ba1su2yMTk////5PP/9sTI////////////3f//////////////2wBDAT1AQFZLVqhdXaj/7Mjs////////////////////////////////////////////////////////////////////wAARCAEOAeADASIAAhEBAxEB/8QAGQAAAwEBAQAAAAAAAAAAAAAAAQIDAAQF/8QANhAAAgIBAwMDAwIFAwMFAAAAAAECEQMSITFBUWEEInETMoGRoSMzQlKxFGLwwdHhNIKSovH/xAAYAQEBAQEBAAAAAAAAAAAAAAAAAQIDBP/EABwRAQEBAAMBAQEAAAAAAAAAAAABEQIhMUESQv/aAAwDAQACEQMRAD8A45x0uuoY6lHXdLj5O3/Tx5df9kc+bG5e6mo8RikZaxBvV2st6bK45NL4l/kjki8b3VdkZRf3cAV9TJubsipNJruHI9Xutt9bEAeK1NJnVBwxQ9z+F1OaMlBcJy/wLvJ9W2B0y9U79sdu7E/1GT+5foCGK1d/rx8PsVWFbppruuwXto55Vf3FIZoz24fZkY4HGXPwbJiknrhz1QO3UAXFJtU+R6I0BgmGgGCYugGCFNUTTAb2Mn3MFJE6VtmgPwZmTS6BS0GvIW7AVGrY1G1M1js6Zmo1gs0yxg2ZJE1cCzG2szLpho0G7ENYQasZQEs11wAzdCtmsBQdgGMEEBjWAUFOkBS2NYG1M10awx3W5Ua7Cl4Ckq5A3T2ZNXDfAN31EcvJtTqginPUzaRKzW2MD6/Bk5MCavca1wlYAv5Y34CjE0AwQAcsvVZOmlfG4n+qyJ/0v8E4QcndWlyFYXJXx2J0dqRz45Xrg9X93JFu+o30tOO24231fQSTuKdpv9/yAE6Q8Met1sn56LuSvcZTbWnfd792UF45J7qvkpBKN7x+G9mavrKoQaa5bluSIeO7G4vqrW3PKKpJfg81bFMeWcHSlS8kxqV30aiWP1EZUpKn54Loy0RR8BoZtigAOwHdbJMGtr7oNfG4BMZTjLhoNAKYNGougGM2ly0DUvP6AYwQ0NUphgDQABMXUwDBMNMAwTDUAwTUXQDBNQ0KYbSw6RoQxRQNp8DUTMPpNpLoQ1DUYaBRqMahoFBN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";
    private static final String TAG = "GtavSetup";
    static {
        System.loadLibrary("gtav_setup");
    }

    private native String nativeInstall(String home, String expected, String disc1, String disc2, String tu);
    private static native boolean nativeIsDataInstalled(String home);

    private static final String[] LABELS = {
        "Game pack (gtav-android-pack.zip from Build for Android on your PC)",
        "Disc 1 (Install) image",
        "Disc 2 (Play) image",
        "Title Update 27 (tu00000009_00000000)",
    };
    private static final int PACK = 0;

    private final Uri[] picked = new Uri[4];
    private final TextView[] pickedText = new TextView[4];
    private final Button[] pickButtons = new Button[4];
    private TextView status, log;
    private ProgressBar progress;
    private Button install;
    private volatile boolean busy;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
        addSetupShortcut();
        for (int i = 0; i < 4; i++) {
            String saved = prefs.getString("uri" + i, null);
            if (saved != null) picked[i] = Uri.parse(saved);
        }
        // Always show the launcher first. The previous version skipped it and immediately
        // launched the game whenever files were installed, hiding all driver controls.
        boolean again = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SETUP, false);
        if (again) showSetupScreen();
        else {
            setContentView(buildUi());
            refreshHome();
        }
    }

    private static final String EXTRA_SETUP = "com.gtavrecomp.app.SETUP";

    /** The launcher shortcut "Run setup again" (dynamic, so the APK needs no resources). */
    private void addSetupShortcut() {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            Intent i = new Intent(this, SetupActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "setup")
                .setShortLabel("Run setup again")
                .setLongLabel("Run setup again (new game pack)")
                .setIntent(i)
                .build();
            Intent gpuIntent = new Intent(this, DriverSettingsActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo gpu = new android.content.pm.ShortcutInfo.Builder(this, "gpu-driver")
                .setShortLabel("GPU driver settings")
                .setLongLabel("GPU driver settings (Turnip)")
                .setIntent(gpuIntent)
                .build();
            sm.setDynamicShortcuts(java.util.Arrays.asList(info, gpu));
        } catch (Exception e) {
            Log.w(TAG, "shortcut: " + e);
        }
    }

    // --- state ----------------------------------------------------------------------------------

    /** Game data folder: the app's external files dir (data/{game,update,user}, spirv/, logs/). */
    private File home() {
        return getExternalFilesDir(null);
    }

    private File packDir() {
        return new File(getFilesDir(), "pack");
    }

    /** null when the installed pack fits this APK, else what is wrong (for the status line). */
    private String packCurrent() {
        File lib = new File(getFilesDir(), "libgtav_game.so");
        File info = new File(packDir(), "pack.txt");
        if (!lib.exists() || !info.exists()) return "No game pack yet.";
        String shell = readShell(info);
        String mine = sha256(new File(getApplicationInfo().nativeLibraryDir, "libgtav_fast.so"));
        if (shell == null || (!shell.equals(mine) && !isKnownCompatibleLegacyShell(shell)))
            return "The game pack was made for another version of this app: this pack is not "
                + "compatible with the installed Android shell.";
        return null;
    }

    /**
     * Legacy v0.3.0 Android pack shell. Its SHA-256 is pinned to the published
     * pack-compatible APK. We compared that APK's exported dynamic symbols with the
     * Turnip shell: every legacy export is still present; the Turnip shell adds only
     * gtav_android_turnip_get_gipa and gtav_android_turnip_report_driver.
     *
     * Do not add hashes here unless the old shell's exported ABI has been checked
     * against the current shell. This keeps the old game pack usable without silently
     * accepting arbitrary mismatched packs.
     */
    private static boolean isKnownCompatibleLegacyShell(String shell) {
        return "840e7e13f62c0bc8d50af807f0e7a9bbff43bad441284bea2867cc1befbd8d90"
            .equalsIgnoreCase(shell);
    }

    private static String readShell(File info) {
        try (BufferedReader r = new BufferedReader(new FileReader(info))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("shell ")) return line.substring(6).trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "pack.txt: " + e);
        }
        return null;
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // --- UI -------------------------------------------------------------------------------------

    private TextView homeStatus;
    private TextView homePack;
    private TextView homeDriver;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable panelBackground(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private Button launcherButton(String caption, int fill, int textColor) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(textColor);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(panelBackground(fill, fill));
        return b;
    }

    /** GTA V launcher inspired by LibertyRecompAndroid: branded hero, live status and clear actions. */
    private View buildUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(7, 10, 16));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(24), dp(18), dp(24), dp(18));
        hero.setBackground(panelBackground(Color.rgb(17, 23, 33), Color.rgb(47, 59, 76)));

        ImageView artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        byte[] artBytes = android.util.Base64.decode(LAUNCHER_ART_BASE64, android.util.Base64.DEFAULT);
        Bitmap artBitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
        if (artBitmap != null) artwork.setImageBitmap(artBitmap);
        artwork.setBackground(panelBackground(Color.rgb(12, 16, 22), Color.rgb(47, 59, 76)));
        artwork.setClipToOutline(true);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(154));
        artParams.bottomMargin = dp(14);
        hero.addView(artwork, artParams);

        TextView eyebrow = text("ROCKSTAR GAMES  •  ANDROID RECOMP", 12);
        eyebrow.setTextColor(Color.rgb(157, 170, 188));
        eyebrow.setGravity(Gravity.CENTER);
        hero.addView(eyebrow);

        TextView grand = text("GRAND THEFT AUTO", 29);
        grand.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        grand.setLetterSpacing(0.045f);
        grand.setGravity(Gravity.CENTER);
        grand.setShadowLayer(dp(8), 0, dp(2), Color.BLACK);
        LinearLayout.LayoutParams logoTop = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logoTop.topMargin = dp(18);
        hero.addView(grand, logoTop);

        LinearLayout logoLine = new LinearLayout(this);
        logoLine.setGravity(Gravity.CENTER);
        TextView five = text("FIVE", 17);
        five.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        five.setTextColor(Color.rgb(16, 22, 17));
        five.setGravity(Gravity.CENTER);
        five.setPadding(dp(14), dp(3), dp(14), dp(3));
        five.setBackground(panelBackground(Color.rgb(143, 190, 91), Color.rgb(183, 220, 137)));
        TextView roman = text("V", 76);
        roman.setTypeface(Typeface.create("serif", Typeface.BOLD));
        roman.setTextColor(Color.rgb(151, 199, 101));
        roman.setShadowLayer(dp(8), 0, dp(2), Color.BLACK);
        roman.setPadding(dp(12), 0, dp(12), 0);
        logoLine.addView(roman);
        logoLine.addView(five);
        hero.addView(logoLine);

        TextView edition = text("ANDROID EDITION", 13);
        edition.setTextColor(Color.rgb(176, 190, 205));
        edition.setLetterSpacing(0.22f);
        edition.setGravity(Gravity.CENTER);
        hero.addView(edition);

        homeStatus = text("Checking game installation…", 15);
        homeStatus.setGravity(Gravity.CENTER);
        homeStatus.setTextColor(Color.rgb(219, 226, 236));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(22);
        hero.addView(homeStatus, statusParams);

        Button play = launcherButton("▶   PLAY GTA V", Color.rgb(151, 194, 95), Color.rgb(12, 18, 12));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        playParams.topMargin = dp(20);
        hero.addView(play, playParams);
        Button importPack = launcherButton("IMPORT GAME PACK", Color.rgb(37, 49, 65), Color.WHITE);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        importParams.topMargin = dp(10);
        hero.addView(importPack, importParams);
        importPack.setOnClickListener(v -> pickPackFromHome());
        play.setOnClickListener(v -> {
            boolean installed = false;
            try { installed = nativeIsDataInstalled(home().getAbsolutePath()) && packCurrent() == null; }
            catch (Exception ignored) { }
            if (installed) startGame();
            else showSetupScreen();
        });

        TextView footer = text("XBOX 360 RECOMPILATION  •  VULKAN", 11);
        footer.setTextColor(Color.rgb(120, 137, 157));
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footParams.topMargin = dp(16);
        hero.addView(footer, footParams);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(18), 0, 0, 0);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        statusCard.setBackground(panelBackground(Color.rgb(17, 23, 33), Color.rgb(47, 59, 76)));
        TextView statusTitle = text("GAME STATUS", 13);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusTitle.setTextColor(Color.rgb(151, 199, 101));
        statusCard.addView(statusTitle);
        homePack = text("Game pack: checking…", 14);
        homePack.setTextColor(Color.rgb(220, 227, 237));
        statusCard.addView(homePack);
        side.addView(statusCard, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout gpuCard = new LinearLayout(this);
        gpuCard.setOrientation(LinearLayout.VERTICAL);
        gpuCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        gpuCard.setBackground(panelBackground(Color.rgb(17, 23, 33), Color.rgb(47, 59, 76)));
        LinearLayout.LayoutParams gpuParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpuParams.topMargin = dp(14);
        TextView gpuTitle = text("VULKAN  /  GPU DRIVER", 13);
        gpuTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        gpuTitle.setTextColor(Color.rgb(151, 199, 101));
        gpuCard.addView(gpuTitle);
        homeDriver = text("Turnip driver settings", 14);
        homeDriver.setTextColor(Color.rgb(220, 227, 237));
        gpuCard.addView(homeDriver);
        Button gpu = launcherButton("GPU DRIVER SETTINGS", Color.rgb(37, 49, 65), Color.WHITE);
        LinearLayout.LayoutParams gpuButtonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        gpuButtonParams.topMargin = dp(10);
        gpuCard.addView(gpu, gpuButtonParams);
        gpu.setOnClickListener(v -> startActivity(new Intent(this, DriverSettingsActivity.class)));
        side.addView(gpuCard, gpuParams);

        Button setup = launcherButton("GAME SETUP  /  MANAGE FILES", Color.rgb(37, 49, 65), Color.WHITE);
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        setupParams.topMargin = dp(14);
        side.addView(setup, setupParams);
        setup.setOnClickListener(v -> showSetupScreen());

        TextView note = text("Your game files and saves stay in the existing app data folder. Opening this launcher will not reinstall or erase them.", 12);
        note.setTextColor(Color.rgb(137, 151, 169));
        note.setPadding(dp(4), dp(12), dp(4), 0);
        side.addView(note);

        // Keep the two-column launcher layout, but let its content define its height.
        // A vertical ScrollView prevents lower side-panel actions from being clipped
        // on short displays and allows normal finger scrolling.
        root.addView(hero, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));
        root.addView(side, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f));

        ScrollView launcherScroll = new ScrollView(this);
        launcherScroll.setFillViewport(true);
        launcherScroll.setClipToPadding(false);
        launcherScroll.setVerticalScrollBarEnabled(true);
        launcherScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        launcherScroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return launcherScroll;
    }

    private void refreshHome() {
        boolean data = false;
        try { data = nativeIsDataInstalled(home().getAbsolutePath()); } catch (Exception ignored) { }
        String pack = packCurrent();
        boolean ready = data && pack == null;
        if (homeStatus != null) homeStatus.setText(ready ? "GAME READY  •  PRESS PLAY" :
            "SETUP REQUIRED  •  OPEN GAME SETUP");
        if (homePack != null) homePack.setText(ready ? "Game files and Android pack: installed" :
            "Game files / Android pack: setup required");
        if (homeDriver != null) {
            boolean turnip = new File(getFilesDir(), "turnip/libvulkan_freedreno.so").isFile();
            boolean enabled = getSharedPreferences("gpu", MODE_PRIVATE).getBoolean("turnip_enabled", false);
            homeDriver.setText(turnip ? (enabled ? "Turnip imported  •  enabled" : "Turnip imported  •  disabled")
                : "System Vulkan  •  Turnip not imported");
        }
    }

    private void showSetupScreen() {
        homeStatus = null;
        homePack = null;
        homeDriver = null;
        setContentView(buildSetupUi());
        refresh();
    }

    private View buildSetupUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(Color.rgb(16, 20, 24));

        TextView title = text("GTA V (Xbox 360 recompilation)", 24);
        box.addView(title);
        box.addView(text("This app contains no game. It installs your own copy: make the game pack on "
            + "your PC (Build for Android in the PC launcher, after setting the game up there), copy "
            + "it and your disc images and title update to this phone (for example to Download), "
            + "choose them below and press Install. About 16 GB is copied and checked.", 14));
        for (int i = 0; i < 4; i++) {
            final int slot = i;
            box.addView(text(LABELS[i], 15));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            pickButtons[i] = new Button(this);
            pickButtons[i].setText("Choose...");
            pickButtons[i].setOnClickListener(v -> pick(slot));
            row.addView(pickButtons[i]);
            pickedText[i] = text("", 13);
            pickedText[i].setPadding(pad / 2, 0, 0, 0);
            row.addView(pickedText[i]);
            box.addView(row);
        }
        install = new Button(this);
        install.setText("Install");
        install.setOnClickListener(v -> onInstall());
        box.addView(install);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        box.addView(progress);
        status = text("", 15);
        box.addView(status);
        log = text("", 12);
        log.setTextColor(Color.rgb(150, 160, 170));
        box.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return scroll;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 8, 0, 4);
        return t;
    }

    private void refresh() {
        String pack = packCurrent();
        boolean data = nativeIsDataInstalled(home().getAbsolutePath());
        for (int i = 0; i < 4; i++) {
            String name = picked[i] != null ? displayName(picked[i]) : null;
            if (i == PACK && pack == null && picked[i] == null) name = "(installed)";
            if (i != PACK && data && picked[i] == null) name = "(game files installed)";
            pickedText[i].setText(name != null ? name : "not chosen");
            pickButtons[i].setEnabled(!busy);
        }
        install.setText(busy ? "Cancel" : "Install");
        if (!busy) {
            if (pack == null && data) status.setText("Ready.");
            else status.setText((pack != null ? pack + " " : "") + (data ? "" : "Game files not installed yet."));
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            // the file was moved or deleted
        }
        return uri.getLastPathSegment();
    }

    private void pick(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, slot);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || request < 0 || request > 3) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "persist " + uri + ": " + e);
        }
        if (request == 4) {
            importPackFromHome(uri);
            return;
        }
        picked[request] = uri;
        prefs.edit().putString("uri" + request, uri.toString()).apply();
        refresh();
    }

    /** Opens the Android game-pack picker directly from the launcher, without entering Setup. */
    private void pickPackFromHome() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, 4);
    }

    private void importPackFromHome(Uri uri) {
        if (busy) return;
        busy = true;
        if (homeStatus != null) homeStatus.setText("IMPORTING GAME PACK…");
        new Thread(() -> {
            cancelled.set(false);
            String error = importPack(uri);
            runOnUiThread(() -> {
                busy = false;
                refreshHome();
                if (error == null) {
                    message("Game pack imported", "The Android game pack was imported. If game discs and Title Update are not installed yet, open GAME SETUP / MANAGE FILES to finish setup.");
                } else {
                    message("Import failed", error);
                }
            });
        }, "gtav-pack-import").start();
    }

    // --- install --------------------------------------------------------------------------------

    private void onInstall() {
        if (busy) {
            cancelled.set(true);
            status.setText("Cancelling...");
            return;
        }
        cancelled.set(false);
        busy = true;
        log.setText("");
        refresh();
        new Thread(() -> {
            String error = runInstall();
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (error == null) {
                    startGame();
                } else {
                    status.setText(cancelled.get() ? "Cancelled. Install again to continue where it stopped." : "Stopped.");
                    if (!cancelled.get()) message("Setup stopped", error);
                }
            });
        }, "gtav-setup").start();
    }

    /**
     * Copies optional PC-free setup tools from APK assets into app-private executable storage.
     * Shared/external storage is mounted noexec, so child tools must live under getFilesDir().
     * A normal release APK without these assets remains compatible with the existing game-pack flow.
     */
    private String prepareOnDeviceTools() {
        final String[] required = {"rexglue", "clang++", "ld.lld"};
        try {
            String[] packaged = getAssets().list("tools");
            if (packaged == null || packaged.length == 0) return null; // legacy pack-based APK
            File root = getFilesDir();
            File llvmBin = new File(root, "tools/llvm/bin");
            if (!llvmBin.exists() && !llvmBin.mkdirs()) return "Could not create private LLVM tools folder.";
            byte[] buffer = new byte[1 << 16];
            for (String name : required) {
                boolean found = false;
                for (String item : packaged) if (name.equals(item)) found = true;
                if (!found) return "The APK has an incomplete on-device tools bundle (missing " + name + ").";
                File target = name.equals("rexglue") ? new File(root, name) : new File(llvmBin, name);
                File temp = new File(target.getParentFile(), name + ".new");
                try (InputStream in = getAssets().open("tools/" + name);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                try (FileInputStream in = new FileInputStream(temp)) {
                    byte[] elf = new byte[20];
                    if (in.read(elf) != elf.length || elf[0] != 0x7f || elf[1] != 'E' ||
                        elf[2] != 'L' || elf[3] != 'F' || elf[4] != 2 || elf[5] != 1 ||
                        (elf[18] & 0xff) != 183 || elf[19] != 0) {
                        temp.delete();
                        return "Invalid Android ARM64 executable in APK: " + name;
                    }
                }
                if (target.exists() && !target.delete()) {
                    temp.delete();
                    return "Could not replace private tool: " + name;
                }
                if (!temp.renameTo(target) || !target.setExecutable(true, true) || !target.canExecute())
                    return "Could not prepare executable tool: " + name;
            }
            onLog("Prepared Android ARM64 codegen, compiler and linker in private storage.");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "prepare on-device tools", e);
            return "Could not prepare on-device tools: " + e;
        }
    }

    /** On the install thread. null = done. */
    private String runInstall() {
        try {
            String toolsError = prepareOnDeviceTools();
            if (toolsError != null) return toolsError;
            if (packCurrent() != null || picked[PACK] != null) {
                if (picked[PACK] == null) return packCurrent();
                String e = importPack(picked[PACK]);
                if (e != null) return e;
                String now = packCurrent();
                if (now != null) return now;
            }
            if (nativeIsDataInstalled(home().getAbsolutePath())) return null;
            for (int i = 1; i < 4; i++) {
                if (picked[i] == null) return "Choose " + LABELS[i] + ".";
            }
            ParcelFileDescriptor[] fds = new ParcelFileDescriptor[4];
            try {
                String[] paths = new String[4];
                for (int i = 1; i < 4; i++) {
                    fds[i] = getContentResolver().openFileDescriptor(picked[i], "r");
                    if (fds[i] == null) return "Could not open " + displayName(picked[i]) + ".";
                    // The installer reads the open document like a file.
                    paths[i] = "/proc/self/fd/" + fds[i].getFd();
                }
                return nativeInstall(home().getAbsolutePath(), new File(packDir(), "expected.txt").getAbsolutePath(),
                                     paths[1], paths[2], paths[3]);
            } finally {
                for (ParcelFileDescriptor f : fds) {
                    if (f != null) try { f.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            return e.toString();
        }
    }

    /**
     * gtav-android-pack.zip -> files/libgtav_game.so (read-only), files/pack/{expected,pack}.txt,
     * <external>/spirv/. The game library is replaced only when the whole pack unpacked.
     */
    private String importPack(Uri uri) {
        onStage("Unpacking the game pack...");
        File stage = new File(getFilesDir(), "pack.new");
        deleteTree(stage);
        stage.mkdirs();
        File spirvNew = new File(home(), "spirv.new");
        deleteTree(spirvNew);
        spirvNew.mkdirs();
        boolean haveLib = false;
        int shaders = 0;
        byte[] buf = new byte[1 << 20];
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (cancelled.get()) return "Cancelled.";
                String name = e.getName();
                if (e.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                File out;
                if (name.equals("libgtav_game.so")) {
                    out = new File(stage, name);
                    haveLib = true;
                } else if (name.equals("expected.txt") || name.equals("pack.txt")) {
                    out = new File(stage, name);
                } else if (name.startsWith("spirv/")) {
                    out = new File(spirvNew, name.substring(6));
                    shaders++;
                } else {
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0;) o.write(buf, 0, n);
                }
            }
        } catch (Exception ex) {
            return "Could not read the game pack: " + ex;
        }
        if (!haveLib || !new File(stage, "pack.txt").exists() || !new File(stage, "expected.txt").exists())
            return "This is not a game pack (gtav-android-pack.zip from Build for Android).";
        // Into place: the library first (read-only, as the system loader prefers), then the rest.
        File lib = new File(getFilesDir(), "libgtav_game.so");
        lib.delete();
        File staged = new File(stage, "libgtav_game.so");
        if (!staged.renameTo(lib)) return "Could not put the game library in place.";
        lib.setReadOnly();
        deleteTree(packDir());
        if (!stage.renameTo(packDir())) return "Could not put the game pack in place.";
        File spirv = new File(home(), "spirv");
        // (The shaders the phone meets later are saved in <external>/shader_cache, a separate folder
        // the next PC build reads back: not touched here.)
        deleteTree(spirv);
        if (!spirvNew.renameTo(spirv)) return "Could not put the shaders in place.";
        onLog("Game pack: game library and " + shaders + " shaders installed.");
        prefs.edit().remove("uri0").apply();
        picked[PACK] = null;
        return null;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    // --- called by libgtav_setup.so on the install thread (InstallUi) ----------------------------

    void onStage(String s) {
        runOnUiThread(() -> { if (status != null) status.setText(s); });
    }

    void onLog(String s) {
        runOnUiThread(() -> {
            if (log == null) return;
            CharSequence old = log.getText();
            String all = old + (old.length() > 0 ? "\n" : "") + s;
            // The last lines only: the full log is data/logs/install.log.
            String[] lines = all.split("\n");
            int from = Math.max(0, lines.length - 12);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) sb.append(i > from ? "\n" : "").append(lines[i]);
            log.setText(sb);
        });
    }

    void onProgress(double f) {
        runOnUiThread(() -> progress.setProgress((int) (f * 1000)));
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean onAsk(String question) {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] yes = {false};
        runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(question).setCancelable(false)
            .setPositiveButton("Yes", (d, w) -> { yes[0] = true; done.countDown(); })
            .setNegativeButton("No", (d, w) -> done.countDown()).show());
        try {
            done.await();
        } catch (InterruptedException e) {
            return false;
        }
        return yes[0];
    }
}
