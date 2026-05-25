package vip.mate.tool.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作区路径沙箱校验器
 * <p>
 * 限制文件工具只能在工作区活动目录（basePath）及其子目录内操作。
 * 跨平台支持：Windows / macOS / Linux 路径统一处理。
 * <p>
 * 使用方式：在文件工具方法开头调用 {@link #validatePath(String)} 获取规范化路径。
 *
 * @author MateClaw Team
 */
@Slf4j
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {}

    /**
     * 校验文件路径是否在当前工作区活动目录范围内。
     * <p>
     * 从 {@link ToolExecutionContext#workspaceBasePath()} 读取当前活动目录。
     * 为空时不限制（向后兼容）。
     *
     * @param rawPath 用户传入的原始路径
     * @return 规范化后的绝对路径
     * @throws IllegalArgumentException 路径不在允许范围内
     */
    public static Path validatePath(String rawPath) {
        return validatePath(rawPath, null);
    }

    /**
     * RFC-063r §2.5: ToolContext-aware overload. Reads the workspace base path
     * from the explicit {@link ChatOrigin} when present; falls back to the
     * legacy {@link ToolExecutionContext} ThreadLocal during the PR-1
     * transition window.
     */
    public static Path validatePath(String rawPath, @Nullable ToolContext ctx) {
        Path normalized = Paths.get(rawPath).toAbsolutePath().normalize();

        String basePath = resolveBasePath(ctx);
        if (basePath == null || basePath.isBlank()) {
            return normalized; // 未配置活动目录，不限制
        }

        Path root = Paths.get(basePath).toAbsolutePath().normalize();

        // 先用 normalize 检查，再尝试 toRealPath 防符号链接逃逸
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path is outside workspace boundary: " + normalized + ", allowed root: " + root);
        }

        // 对已存在的路径，解析符号链接后再次校验
        try {
            if (normalized.toFile().exists()) {
                Path realPath = normalized.toRealPath();
                Path realRoot = root.toFile().exists() ? root.toRealPath() : root;
                if (!realPath.startsWith(realRoot)) {
                    throw new IllegalArgumentException(
                            "Path escapes workspace via symlink: " + realPath + ", allowed root: " + realRoot);
                }
                return realPath;
            }
        } catch (IOException e) {
            log.debug("[WorkspacePathGuard] 无法解析真实路径（文件可能不存在）: {}", normalized);
        }

        return normalized;
    }

    /**
     * 获取当前工作区活动目录的 Path（用于设置 Shell 工作目录等）。
     *
     * @return 活动目录 Path，未配置时返回 null
     */
    public static Path getWorkingDirectory() {
        return getWorkingDirectory(null);
    }

    /**
     * RFC-063r §2.5: ToolContext-aware variant — prefer the explicit
     * {@link ChatOrigin} workspaceBasePath when available.
     */
    public static Path getWorkingDirectory(@Nullable ToolContext ctx) {
        String basePath = resolveBasePath(ctx);
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        return Paths.get(basePath).toAbsolutePath().normalize();
    }

    /**
     * Validate that a shell command does not reference filesystem locations
     * outside the active workspace boundary. When no workspace basePath is
     * configured, the check is a no-op (matching {@link #validatePath} semantics).
     *
     * <p>The check is a static scan of the literal command string. It rejects:
     * <ul>
     *   <li>any absolute path token (e.g. {@code /etc/passwd}, {@code >/tmp/x},
     *       {@code cd /var}) whose normalized form is not under the workspace
     *       root — even when nested inside command substitution {@code $(...)}
     *       or backticks;</li>
     *   <li>tilde expansion ({@code ~}, {@code ~/...}) — always resolves to
     *       {@code $HOME}, which sits outside the workspace;</li>
     *   <li>references to environment variables ({@code $HOME}, {@code ${USER}},
     *       {@code $TMPDIR}, etc.) that typically resolve outside the workspace.</li>
     * </ul>
     *
     * <p><b>Limitations</b> — the static scan is a best-effort defense, not a
     * true filesystem sandbox. Obfuscated forms ({@code /e''tc/passwd},
     * variable concatenation like {@code X=/etc; cat $X/passwd}, base64-decoded
     * paths) can still slip through. The agent is not expected to produce
     * such forms in normal use, but a fully adversarial caller would need a
     * real process sandbox (sandbox-exec / firejail / bwrap) on top of this
     * check.
     *
     * @param command the shell command line as it will be passed to {@code sh -c}
     * @throws IllegalArgumentException when the command references a location
     *         outside the workspace boundary
     */
    public static void validateShellCommand(String command) {
        validateShellCommand(command, null);
    }

    /** ToolContext-aware overload — see {@link #validateShellCommand(String)}. */
    public static void validateShellCommand(String command, @Nullable ToolContext ctx) {
        if (command == null || command.isEmpty()) return;
        String basePath = resolveBasePath(ctx);
        if (basePath == null || basePath.isBlank()) return;
        Path root = Paths.get(basePath).toAbsolutePath().normalize();

        // 1. Tilde — expands to $HOME, always outside a non-$HOME workspace.
        if (TILDE_REF.matcher(command).find()) {
            throw new IllegalArgumentException(
                    "Shell command uses tilde (~) expansion which resolves outside the workspace boundary: "
                            + truncateForError(command));
        }

        // 2. Env-var refs to locations that typically resolve outside the workspace.
        Matcher envMatch = OUTSIDE_ENV_VAR.matcher(command);
        if (envMatch.find()) {
            throw new IllegalArgumentException(
                    "Shell command references environment variable " + envMatch.group()
                            + " which may resolve outside the workspace boundary");
        }

        // 3. Absolute-path tokens, including those nested inside $(...) or `...`.
        Matcher pathMatch = ABS_PATH_TOKEN.matcher(command);
        while (pathMatch.find()) {
            String candidate = pathMatch.group(1);
            // Strip trailing punctuation that the shell would treat as a separator
            // but the regex captured into the path (defensive trim — the character
            // class excludes most, this catches edge cases like a path followed
            // by a comma in a sentence).
            while (candidate.length() > 1) {
                char tail = candidate.charAt(candidate.length() - 1);
                if (tail == ',' || tail == ':' || tail == '.' || tail == ')' || tail == ']') {
                    candidate = candidate.substring(0, candidate.length() - 1);
                } else {
                    break;
                }
            }
            Path normalized;
            try {
                normalized = Paths.get(candidate).normalize();
            } catch (Exception ex) {
                // Unparseable as a path — leave it alone, not our concern.
                continue;
            }
            if (!normalized.startsWith(root)) {
                throw new IllegalArgumentException(
                        "Shell command references path outside workspace boundary: "
                                + normalized + ", allowed root: " + root);
            }
        }
    }

    /**
     * Match absolute path tokens — a leading slash that starts a fresh token
     * (preceded by start-of-string, whitespace, a shell separator, or an
     * opening quote/parenthesis/backtick) and runs until the next shell
     * separator or quote. The {@code (?<!:)} lookbehind excludes the second
     * slash of a URL protocol (e.g. {@code https://host/path}) so URLs aren't
     * mistaken for filesystem paths.
     */
    private static final Pattern ABS_PATH_TOKEN = Pattern.compile(
            "(?:^|[\\s|&;<>(`\"'={}])(?<!:)(/[^\\s|&;<>()\"'`{}=]+)");

    /** Bare tilde or tilde at the start of a path token: {@code ~}, {@code ~/foo}, {@code "~/bar"}. */
    private static final Pattern TILDE_REF = Pattern.compile(
            "(?:^|[\\s|&;<>(`\"'={}])~(?=[/\\s|&;<>)`\"'$]|$)");

    /**
     * Env-var references that almost always point outside a project-scoped
     * workspace. {@code $PATH} is on the list because writing to a directory
     * on {@code $PATH} is a privilege-escalation vector.
     */
    private static final Pattern OUTSIDE_ENV_VAR = Pattern.compile(
            "\\$\\{?(HOME|USER|LOGNAME|TMPDIR|TMP|TEMP|PWD|OLDPWD|PATH|MAIL)\\b");

    private static String truncateForError(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /**
     * Resolve the active workspace base path. Order of preference:
     * <ol>
     *   <li>ChatOrigin from ToolContext (RFC-063r §2.5)</li>
     *   <li>Legacy {@link ToolExecutionContext} ThreadLocal (PR-1 transition)</li>
     * </ol>
     */
    private static String resolveBasePath(@Nullable ToolContext ctx) {
        if (ctx != null) {
            ChatOrigin origin = ChatOrigin.from(ctx);
            if (origin.workspaceBasePath() != null && !origin.workspaceBasePath().isBlank()) {
                return origin.workspaceBasePath();
            }
        }
        return ToolExecutionContext.workspaceBasePath();
    }
}
