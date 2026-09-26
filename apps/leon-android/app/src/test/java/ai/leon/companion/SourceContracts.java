package ai.leon.companion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class SourceContracts {
    private SourceContracts() {
    }

    static String file(String relative) throws Exception {
        File[] candidates = {
                new File(relative),
                new File("apps/leon-android/" + relative),
                new File("apps/leon-android/app/" + relative)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("File not found: " + relative);
    }

    static String source(String relative) throws Exception {
        File[] candidates = {
                new File("app/src/main/java/" + relative),
                new File("src/main/java/" + relative),
                new File("apps/leon-android/app/src/main/java/" + relative)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source file not found: " + relative);
    }

    static String manifestSource() throws Exception {
        File[] candidates = {
                new File("src/main/AndroidManifest.xml"),
                new File("app/src/main/AndroidManifest.xml"),
                new File("apps/leon-android/app/src/main/AndroidManifest.xml")
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("AndroidManifest.xml not found");
    }
}
