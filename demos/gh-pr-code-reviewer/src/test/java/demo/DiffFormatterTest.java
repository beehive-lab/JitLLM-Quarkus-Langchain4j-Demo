package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DiffFormatterTest {

    private static final String DIFF = """
            diff --git a/src/Main.java b/src/Main.java
            index 1111111..2222222 100644
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -10,3 +10,4 @@ class Main {
                 int a = 1;
            -    int b = 2;
            +    int b = 3;
            +    int c = 4;
                 return;
            \\ No newline at end of file
            diff --git a/NEW.md b/NEW.md
            new file mode 100644
            --- /dev/null
            +++ b/NEW.md
            @@ -0,0 +1 @@
            +hello
            diff --git a/package-lock.json b/package-lock.json
            --- a/package-lock.json
            +++ b/package-lock.json
            @@ -1 +1 @@
            -{}
            +{ }
            diff --git a/logo.png b/logo.png
            Binary files a/logo.png and b/logo.png differ
            """;

    @Test
    void numbersAddedAndUnchangedLinesByTheirNewFileLine() {
        String text = DiffFormatter.format(DIFF, 10_000).text();

        assertTrue(text.contains("### src/Main.java (modified)\n"), text);
        assertTrue(text.contains("   10       int a = 1;\n"), text);
        assertTrue(text.contains("      -     int b = 2;\n"), text);
        assertTrue(text.contains("   11 +     int b = 3;\n"), text);
        assertTrue(text.contains("   12 +     int c = 4;\n"), text);
        assertTrue(text.contains("   13       return;\n"), text);
        assertTrue(!text.contains("No newline"), text);
    }

    @Test
    void marksNewFiles() {
        String text = DiffFormatter.format(DIFF, 10_000).text();

        assertTrue(text.contains("### NEW.md (added)\n"), text);
        assertTrue(text.contains("    1 + hello\n"), text);
    }

    @Test
    void skipsLockFilesAndBinaries() {
        DiffFormatter.Result result = DiffFormatter.format(DIFF, 10_000);

        assertEquals(2, result.filesIncluded());
        assertEquals(List.of("package-lock.json", "logo.png"), result.skipped());
        assertEquals(List.of(), result.overBudget());
    }

    @Test
    void leavesOutWholeFilesThatDoNotFitTheBudget() {
        DiffFormatter.Result result = DiffFormatter.format(DIFF, 80);

        assertEquals(1, result.filesIncluded());
        assertEquals(List.of("src/Main.java"), result.overBudget());
        assertTrue(result.text().startsWith("### NEW.md"), result.text());
    }
}
