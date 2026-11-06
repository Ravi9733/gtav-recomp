package com.gtavrecomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
        if (shell == null || !shell.equals(mine))
            return "The game pack was made for another version of this app: run Build for Android "
                + "again with the download that has this APK.";
        return null;
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
