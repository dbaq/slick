package tv.slick;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Whole-file UTF-8 reads and writes. java.nio.file only arrived in Android 8; the garage TV runs 7.1. */
final class TextFile {

    private TextFile() {
    }

    static String read(File f) throws IOException {
        byte[] bytes = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
