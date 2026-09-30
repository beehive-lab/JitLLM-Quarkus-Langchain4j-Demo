package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a unified diff for the model.
 *
 * <p>
 * Two things a model does badly on a raw diff are done here instead:
 * <ul>
 * <li><b>Line numbers.</b> Every added and context line is prefixed with its line number in the new
 * file, so findings can cite {@code file:line} without the model counting from hunk headers.</li>
 * <li><b>Size.</b> The prompt has to fit in the context (the model's {@code max-tokens}). Lock files,
 * binaries and generated files are dropped, then whole files are included until the budget runs out;
 * the rest are listed as not reviewed.</li>
 * </ul>
 */
final class DiffFormatter {

    private static final Pattern FILE_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");
    private static final Pattern SKIPPED = Pattern.compile(
            "(^|/)(package-lock\\.json|yarn\\.lock|pnpm-lock\\.yaml|Cargo\\.lock|go\\.sum|poetry\\.lock|Gemfile\\.lock)$"
                    + "|\\.(min\\.js|min\\.css|map|svg|png|jpe?g|gif|ico|pdf|jar|gguf|bin)$");

    /** The formatted diff, and what was left out of it. */
    record Result(String text, int filesIncluded, List<String> skipped, List<String> overBudget) {
    }

    private DiffFormatter() {
    }

    static Result format(String diff, int maxChars) {
        List<String> skipped = new ArrayList<>();
        List<String> overBudget = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int included = 0;
        for (FileDiff file : split(diff)) {
            file.scanHeader();
            if (file.binary || SKIPPED.matcher(file.path).find()) {
                skipped.add(file.path);
                continue;
            }
            String formatted = file.format();
            if (out.length() + formatted.length() > maxChars) {
                overBudget.add(file.path);
                continue;
            }
            out.append(formatted);
            included++;
        }
        return new Result(out.toString(), included, skipped, overBudget);
    }

    private static List<FileDiff> split(String diff) {
        List<FileDiff> files = new ArrayList<>();
        FileDiff current = null;
        for (String line : diff.split("\n", -1)) {
            Matcher header = FILE_HEADER.matcher(line);
            if (header.matches()) {
                current = new FileDiff(header.group(2));
                files.add(current);
            } else if (current != null) {
                current.lines.add(line);
            }
        }
        return files;
    }

    private static final class FileDiff {
        final String path;
        final List<String> lines = new ArrayList<>();
        String status = "modified";
        boolean binary;

        FileDiff(String path) {
            this.path = path;
        }

        /** Reads the extended header lines, which come before the first hunk. */
        void scanHeader() {
            for (String line : lines) {
                if (line.startsWith("@@")) {
                    return;
                } else if (line.startsWith("new file mode")) {
                    status = "added";
                } else if (line.startsWith("deleted file mode")) {
                    status = "deleted";
                } else if (line.startsWith("rename from ")) {
                    status = "renamed from " + line.substring("rename from ".length());
                } else if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                    binary = true;
                }
            }
        }

        String format() {
            StringBuilder body = new StringBuilder();
            int newLine = 0;
            boolean inHunk = false;
            for (String line : lines) {
                Matcher hunk = HUNK_HEADER.matcher(line);
                if (hunk.matches()) {
                    inHunk = true;
                    newLine = Integer.parseInt(hunk.group(1));
                    body.append(line).append('\n');
                    continue;
                }
                // Before the first hunk: the extended header (index, ---, +++), read by scanHeader()
                if (!inHunk) {
                    continue;
                }
                if (line.startsWith("+")) {
                    body.append(String.format("%5d + %s\n", newLine++, line.substring(1)));
                } else if (line.startsWith("-")) {
                    body.append("      - ").append(line.substring(1)).append('\n');
                } else if (line.startsWith(" ")) {
                    body.append(String.format("%5d   %s\n", newLine++, line.substring(1)));
                }
                // anything else, such as "\ No newline at end of file", carries no code
            }
            return "### " + path + " (" + status + ")\n" + body + "\n";
        }
    }
}
