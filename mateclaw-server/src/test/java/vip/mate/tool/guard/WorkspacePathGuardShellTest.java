package vip.mate.tool.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import vip.mate.tool.builtin.ToolExecutionContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link WorkspacePathGuard#validateShellCommand(String)} — the
 * static command-string scan that backstops {@code ShellExecuteTool} so an
 * absolute-path reference in the shell command cannot reach outside the
 * configured workspace boundary, even though the shell process itself has
 * full filesystem permissions.
 *
 * <p>The boundary is read via {@link ToolExecutionContext#workspaceBasePath()}.
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths in these cases
class WorkspacePathGuardShellTest {

    private static final String WORKSPACE = "/tmp/ws-guard-shell-test";

    @BeforeEach
    void setup() {
        ToolExecutionContext.set("conv-test", "test-user", WORKSPACE);
    }

    @AfterEach
    void teardown() {
        ToolExecutionContext.clear();
    }

    // ==================== No-op when sandbox absent ====================

    @Test
    @DisplayName("No workspace configured → all commands pass")
    void noWorkspace_noop() {
        ToolExecutionContext.clear();
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("rm -rf /"));
    }

    @Test
    @DisplayName("Null or empty command → no-op")
    void nullOrEmpty_noop() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(null));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(""));
    }

    // ==================== In-boundary commands pass ====================

    @Test
    @DisplayName("Relative paths and in-workspace absolute paths pass")
    void inBoundary_pass() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("ls -la"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat foo.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat subdir/bar.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cat " + WORKSPACE + "/foo.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cd " + WORKSPACE + "/subdir && ls"));
    }

    @Test
    @DisplayName("URLs are not mistaken for filesystem paths")
    void urls_pass() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "curl -s https://example.com/api/data"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "wget -O out.txt http://host:8080/path/to/file"));
    }

    // ==================== Out-of-boundary absolute paths blocked ====================

    @Test
    @DisplayName("Absolute path outside workspace → rejected")
    void absoluteOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("head -3 /Users/someone/code/secret.md"));
    }

    @Test
    @DisplayName("Output redirection to outside path → rejected")
    void redirection_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo evil > /tmp/leak.txt"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ls >> /var/log/sneak.log"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("grep foo < /etc/hosts"));
    }

    @Test
    @DisplayName("cd / pushd to outside path → rejected")
    void cdOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd /etc && ls"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("pushd /var/spool"));
    }

    @Test
    @DisplayName("ln -s to an outside target → rejected")
    void symlinkCreate_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ln -sf /etc/passwd alias"));
    }

    @Test
    @DisplayName("Pipe with outside path on either side → rejected")
    void pipeOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /etc/passwd | grep root"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("env | tee /tmp/dump.txt"));
    }

    @Test
    @DisplayName("Quoted absolute path → rejected")
    void quotedAbsolute_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat \"/etc/passwd\""));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat '/etc/passwd'"));
    }

    @Test
    @DisplayName("Command substitution $(...) with outside path → rejected")
    void commandSubstOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo $(cat /etc/passwd)"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("X=`head /etc/hostname`; echo $X"));
    }

    // ==================== Tilde + env-var rejection ====================

    @Test
    @DisplayName("Tilde expansion → rejected")
    void tilde_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat ~/.zshrc"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd ~ && ls"));
    }

    @Test
    @DisplayName("$HOME / ${HOME} / $TMPDIR / $PATH → rejected")
    void envVarOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat $HOME/.zshrc"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ls ${HOME}/Documents"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("touch $TMPDIR/leak"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo bad > $PATH/evil"));
    }

    @Test
    @DisplayName("Other env vars not on the deny list are allowed")
    void unrelatedEnvVar_pass() {
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("echo $LANG"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("printf '%s\\n' \"$MY_FLAG\""));
    }
}
