package ai.leon.companion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SourceContracts {
    private SourceContracts() {
    }

    static String file(String relative) throws Exception {
        String userDir = System.getProperty("user.dir");
        return file(relative, new File(userDir), userDir);
    }

    static String file(String relative, File startDirectory) throws Exception {
        return file(relative, startDirectory, startDirectory.getAbsolutePath());
    }

    private static String file(String relative, File startDirectory, String userDir) throws Exception {
        List<File> candidates = new ArrayList<>();
        addCandidate(candidates, new File(startDirectory, relative));
        addCandidate(candidates, new File(startDirectory, "../" + relative));
        addCandidate(candidates, new File(startDirectory, "../../" + relative));
        addCandidate(candidates, new File(startDirectory, "apps/leon-android/" + relative));
        addCandidate(candidates, new File(startDirectory, "apps/leon-android/app/" + relative));

        File current = startDirectory.getAbsoluteFile();
        while (current != null) {
            addCandidate(candidates, new File(current, relative));
            if (looksLikeRepositoryRoot(current)) {
                break;
            }
            current = current.getParentFile();
        }

        Set<String> attempted = new LinkedHashSet<>();
        for (File file : candidates) {
            attempted.add(file.getAbsolutePath());
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("File not found: " + relative
                + " (user.dir=" + userDir
                + ", attempted=" + attempted + ")");
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

    private static void addCandidate(List<File> candidates, File file) {
        candidates.add(file.getAbsoluteFile());
    }

    private static boolean looksLikeRepositoryRoot(File directory) {
        return new File(directory, ".github").isDirectory()
                && new File(directory, "apps/leon-android").isDirectory();
    }
}
