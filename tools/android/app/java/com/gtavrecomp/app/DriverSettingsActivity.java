package com.gtavrecomp.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports a user-supplied ARM64 Turnip driver into app-private storage. */
public final class DriverSettingsActivity extends Activity {
    private static final int PICK_DRIVER = 41;
    private static final long MAX_TOTAL = 512L << 20;
    private static final long MAX_ENTRY = 256L << 20;
    private CheckBox enabled;
    private TextView status;
    private Button importButton;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("gpu", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.rgb(12, 17, 27));
        TextView title = text("GPU DRIVER SETTINGS", 23);
        root.addView(title);
        root.addView(text("Optional Turnip driver for Adreno. Import a trusted ARM64 Turnip ZIP. "
            + "The driver is stored in this app's private files; game data and saves are not changed.", 15));
        status = text("", 14);
        root.addView(status);
        importButton = new Button(this);
        importButton.setText("Import Turnip driver ZIP");
        importButton.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            startActivityForResult(i, PICK_DRIVER);
        });
        root.addView(importButton);
        enabled = new CheckBox(this);
        enabled.setText("Use Turnip on next game launch");
        enabled.setTextColor(Color.WHITE);
        enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(80, 160, 255)));
        enabled.setOnCheckedChangeListener((v, checked) -> {
            boolean ready = new File(getFilesDir(), "turnip/libvulkan_freedreno.so").isFile();
            if (checked && !ready) {
                enabled.setChecked(false);
                status.setText("Import a valid Turnip ZIP first.");
                return;
            }
            prefs.edit().putBoolean("turnip_enabled", checked).apply();
            status.setText(checked ? "Turnip selected. It will be loaded on the next launch." :
                "System Vulkan will be used on the next launch.");
        });
        root.addView(enabled);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 12, 0, 10);
        return t;
    }

    private void refresh() {
        boolean ready = new File(getFilesDir(), "turnip/libvulkan_freedreno.so").isFile();
        enabled.setEnabled(ready);
        enabled.setChecked(ready && prefs.getBoolean("turnip_enabled", false));
        status.setText(ready ? "Turnip driver imported. Current setting: " +
            (enabled.isChecked() ? "enabled" : "disabled") :
            "No Turnip driver imported. System Vulkan remains the safe default.");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DRIVER || result != RESULT_OK || data == null || data.getData() == null) return;
        importButton.setEnabled(false);
        status.setText("Checking and importing driver into private storage...");
        Uri uri = data.getData();
        new Thread(() -> {
            String error = importZip(uri);
            runOnUiThread(() -> {
                importButton.setEnabled(true);
                status.setText(error == null ? "Turnip driver imported. Enable it and restart the game to use it." : error);
                refreshAfterImport(error == null);
            });
        }, "turnip-import").start();
    }

    private void refreshAfterImport(boolean success) {
        boolean ready = new File(getFilesDir(), "turnip/libvulkan_freedreno.so").isFile();
        enabled.setEnabled(ready);
        enabled.setChecked(ready && prefs.getBoolean("turnip_enabled", false));
        if (success) status.setText("ARM64 Turnip driver imported. Enable it, then restart the game.");
    }

    private String importZip(Uri uri) {
        File stage = new File(getFilesDir(), "turnip.new");
        File target = new File(getFilesDir(), "turnip");
        File backup = new File(getFilesDir(), "turnip.old");
        deleteTree(stage);
        if (!stage.mkdirs()) return "Could not create private driver folder.";
        long total = 0;
        int files = 0;
        boolean foundDriver = false;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[1 << 16];
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            if (raw == null) return "Could not open the selected ZIP.";
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
                if (lowerPath.contains("armeabi") || lowerPath.contains("armv7") ||
                    lowerPath.contains("x86") || lowerPath.contains("mips")) continue;
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.endsWith(".so")) continue;
                if (name.isEmpty() || name.equals(".") || name.equals("..") || !names.add(name))
                    return fail(stage, "ZIP contains an unsafe or duplicate library name.");
                File out = new File(stage, name);
                long entryBytes = 0;
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buffer)) != -1;) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > MAX_ENTRY || total > MAX_TOTAL)
                            return fail(stage, "Driver ZIP is too large (maximum extracted size is 512 MiB).");
                        stream.write(buffer, 0, n);
                    }
                    stream.getFD().sync();
                }
                if (!isArm64Elf(out))
                    return fail(stage, name + " is not a valid 64-bit ARM ELF library.");
                files++;
                if (name.equals("libvulkan_freedreno.so")) foundDriver = true;
            }
        } catch (Exception e) {
            return fail(stage, "Could not import Turnip ZIP: " + e);
        }
        if (!foundDriver || files == 0)
            return fail(stage, "ZIP must contain ARM64 libvulkan_freedreno.so and its required .so dependencies.");
        deleteTree(backup);
        if (target.exists() && !target.renameTo(backup))
            return fail(stage, "Could not safely replace the previous driver.");
        if (!stage.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            return fail(stage, "Could not activate the imported driver files.");
        }
        deleteTree(backup);
        return null;
    }

    private boolean isArm64Elf(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] h = new byte[20];
            if (in.read(h) != h.length) return false;
            return h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
                && h[4] == 2 && h[5] == 1 && (h[18] & 0xff) == 183 && h[19] == 0;
        } catch (Exception e) { return false; }
    }

    private String fail(File stage, String message) {
        deleteTree(stage);
        return message;
    }

    private void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
