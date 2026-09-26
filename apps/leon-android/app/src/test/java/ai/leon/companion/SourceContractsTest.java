package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class SourceContractsTest {
    @Test
    public void fileWalksUpwardToRepositoryRootMarkers() throws Exception {
        Path repoRoot = Files.createTempDirectory("source-contracts-repo");
        Files.createDirectories(repoRoot.resolve(".github/workflows"));
        Files.createDirectories(repoRoot.resolve("apps/leon-android"));
        Path target = repoRoot.resolve("docs/LEON_ANDROID_AUDIT.md");
        Files.createDirectories(target.getParent());
        Files.write(target, "lock/unlock checklist".getBytes(StandardCharsets.UTF_8));

        Path workingDir = repoRoot.resolve("apps/leon-android/app/build/tmp/testDebugUnitTest");
        Files.createDirectories(workingDir);

        assertEquals("lock/unlock checklist",
                SourceContracts.file("docs/LEON_ANDROID_AUDIT.md", workingDir.toFile()));
    }

    @Test
    public void fileFailureIncludesUserDirAndAttemptedPaths() throws Exception {
        Path repoRoot = Files.createTempDirectory("source-contracts-missing");
        Files.createDirectories(repoRoot.resolve(".github"));
        Files.createDirectories(repoRoot.resolve("apps/leon-android"));
        Path workingDir = repoRoot.resolve("apps/leon-android/app/build/tmp/testDebugUnitTest");
        Files.createDirectories(workingDir);

        try {
            SourceContracts.file("docs/missing.md", workingDir.toFile());
        } catch (AssertionError error) {
            assertTrue(error.getMessage().contains("user.dir=" + workingDir));
            assertTrue(error.getMessage().contains(repoRoot.resolve("docs/missing.md").toString()));
            return;
        }

        throw new AssertionError("Expected SourceContracts.file to fail for a missing contract");
    }
}
