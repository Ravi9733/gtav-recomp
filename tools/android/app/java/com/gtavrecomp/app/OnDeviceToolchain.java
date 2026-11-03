package com.gtavrecomp.app;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;

/**
 * Runtime preflight for optional Android ARM64 build tools packaged in nativeLibraryDir.
 * This deliberately does not start a game build: the ReXGlue/game/shader pipeline is not yet
 * fully on-device. A passing probe proves only that these individual executables can start.
 */
final class OnDeviceToolchain {
    private OnDeviceToolchain() {}

    static String probe(Context context) {
        File dir = new File(context.getApplicationInfo().nativeLibraryDir);
        StringBuilder report = new StringBuilder();
        check(report, new File(dir, "libclang.so"), true);
        check(report, new File(dir, "libld.lld.so"), true);
        check(report, new File(dir, "librexglue.so"), false);
        return report.toString();
    }

    private static void check(StringBuilder out, File executable, boolean required) {
        String label = executable.getName();
        if (!executable.isFile()) {
            out.append(label).append(": ")
                .append(required ? "MISSING" : "not packaged (optional in this APK)")
                .append('\n');
            return;
        }
        if (!executable.canExecute()) {
            out.append(label).append(": present but not executable\n");
            return;
        }
        if (!isAarch64Elf(executable)) {
            out.append(label).append(": wrong format (expected Android ARM64 ELF)\n");
            return;
        }
        File output = null;
        try {
            output = File.createTempFile("gtav-tool-", ".log");
            Process process = new ProcessBuilder(executable.getAbsolutePath(), "--version")
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start();
            boolean ended = process.waitFor(15, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly();
                out.append(label).append(": launch timed out\n");
            } else if (process.exitValue() != 0) {
                out.append(label).append(": launch failed (exit ")
                    .append(process.exitValue()).append(")\n");
            } else {
                out.append(label).append(": executable OK");
                String version = readFirstLine(output);
                if (!version.isEmpty()) out.append(" — ").append(version);
                out.append('\n');
            }
        } catch (Exception e) {
            out.append(label).append(": launch error — ")
                .append(e.getClass().getSimpleName()).append(": ")
                .append(String.valueOf(e.getMessage())).append('\n');
        } finally {
            if (output != null) output.delete();
        }
    }

    private static boolean isAarch64Elf(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 20 || in.readUnsignedByte() != 0x7f
                    || in.readUnsignedByte() != 'E' || in.readUnsignedByte() != 'L'
                    || in.readUnsignedByte() != 'F') return false;
            in.seek(18);
            int low = in.readUnsignedByte();
            int high = in.readUnsignedByte();
            return (low | (high << 8)) == 183; // EM_AARCH64
        } catch (Exception e) {
            return false;
        }
    }

    private static String readFirstLine(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
