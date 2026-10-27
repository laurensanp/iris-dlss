package com.irisdlss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * diagnosticRecording=true: a Java Flight Recording ("profile" settings) of the last 10 minutes, written to
 * iris-dlss/recordings/fps-&lt;time&gt;.jfr when the game closes. Started from code because launchers like Lunar
 * offer no JVM arguments (and disable attaching, so jcmd can't start one either).
 */
public final class DiagnosticRecording {

    private static Recording recording;

    private DiagnosticRecording() {
    }

    public static synchronized void start(Path gameDir) {
        if (recording != null) {
            return;
        }
        try {
            Path dir = gameDir.resolve("iris-dlss").resolve("recordings");
            Files.createDirectories(dir);
            Path file = dir.resolve("fps-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jfr");
            Recording r = new Recording(Configuration.getConfiguration("profile"));
            r.setName("iris-dlss");
            r.setToDisk(true);
            r.setMaxAge(Duration.ofMinutes(10));
            r.setDestination(file);
            r.start();
            recording = r;
            Runtime.getRuntime().addShutdownHook(new Thread(DiagnosticRecording::stop, "iris-dlss recording"));
            IrisDlss.LOGGER.info("Iris DLSS: diagnostic recording on, written to {} when the game closes", file);
        } catch (IOException | ParseException | RuntimeException | LinkageError e) {
            IrisDlss.LOGGER.warn("Iris DLSS: could not start the diagnostic recording", e);
        }
    }

    private static synchronized void stop() {
        if (recording != null) {
            recording.stop(); // writes the destination file
            recording.close();
            recording = null;
        }
    }
}
