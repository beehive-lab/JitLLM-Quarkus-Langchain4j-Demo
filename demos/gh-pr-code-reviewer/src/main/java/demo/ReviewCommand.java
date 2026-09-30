package demo;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.quarkiverse.langchain4j.runtime.aiservice.ChatEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "gh-pr-code-reviewer", mixinStandardHelpOptions = true, version = "1.0",
        description = "Review a GitHub pull request with a model running locally on the GPU through JitLLM.")
@ActivateRequestContext
public class ReviewCommand implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(ReviewCommand.class);

    @Parameters(paramLabel = "PR_URL", description = "Pull request link, e.g. https://github.com/owner/repo/pull/123")
    String link;

    @Option(names = "--max-diff-chars",
            description = "Diff budget in characters. Defaults to gh-pr-code-reviewer.max-diff-chars (60000).")
    Integer maxDiffChars;

    @Inject
    @RestClient
    GitHubClient github;

    @Inject
    CodeReviewer reviewer;

    @Inject
    CodeReviewerConfig config;

    @Override
    public Integer call() {
        PullRequestLink pr;
        try {
            pr = PullRequestLink.parse(link);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        LOG.infof("Fetching %s from the GitHub API", pr);
        GitHubClient.PullRequest info;
        String diff;
        try {
            info = github.pullRequest(pr.owner(), pr.repo(), pr.number());
            diff = github.diff(pr.owner(), pr.repo(), pr.number());
        } catch (WebApplicationException e) {
            System.err.println(describe(pr, e.getResponse().getStatus()));
            return 1;
        }

        int budget = maxDiffChars != null ? maxDiffChars : config.maxDiffChars();
        DiffFormatter.Result formatted = DiffFormatter.format(diff, budget);
        LOG.infof("Prepared %,d characters of annotated diff from %d of %d file(s) (budget %,d)",
                formatted.text().length(), formatted.filesIncluded(), info.changedFiles(), budget);

        System.out.printf("%s — %s%n", pr, info.title());
        System.out.printf("%s by @%s, %s into %s%n", info.htmlUrl(), info.user().login(), info.head().label(),
                info.base().ref());
        System.out.printf("%d file(s) changed, +%d -%d; reviewing %d file(s)%n",
                info.changedFiles(), info.additions(), info.deletions(), formatted.filesIncluded());
        printList("Skipped (lock, generated or binary)", formatted.skipped());
        printList("Not reviewed (over the diff budget)", formatted.overBudget());
        System.out.println();

        if (formatted.filesIncluded() == 0) {
            System.out.println("Nothing to review.");
            return 0;
        }

        return review(pr, info, formatted);
    }

    private int review(PullRequestLink pr, GitHubClient.PullRequest info, DiffFormatter.Result formatted) {
        LOG.info("Starting the review. The model reads the whole prompt before its first token; with a model"
                + " that runs locally, the first request also loads it.");
        ReviewPrinter printer = new ReviewPrinter(System.out, System.console() != null);
        long start = System.nanoTime();
        AtomicLong firstToken = new AtomicLong();

        // Loading the model and reading a long prompt take a while with nothing to show: report progress
        Duration interval = config.progressInterval();
        Cancellable progress = Multi.createFrom().ticks().startingAfter(interval).every(interval)
                .subscribe().with(tick -> {
                    if (firstToken.get() == 0) {
                        LOG.infof("... %.0f s, no output yet", seconds(start, System.nanoTime()));
                    }
                });

        ChatResponse response;
        try {
            response = reviewer.review(
                    info.title(),
                    pr.repository(),
                    info.user().login(),
                    info.head().label(),
                    info.base().ref(),
                    description(info.body()),
                    coverage(formatted),
                    formatted.text())
                    .onItem().invoke(event -> {
                        if (event instanceof ChatEvent.PartialThinkingEvent thinking) {
                            firstToken.compareAndSet(0, System.nanoTime());
                            printer.thinking(thinking.getText());
                        } else if (event instanceof ChatEvent.PartialResponseEvent partial) {
                            firstToken.compareAndSet(0, System.nanoTime());
                            printer.response(partial.getChunk());
                        }
                    })
                    .filter(ChatEvent.ChatCompletedEvent.class::isInstance)
                    .map(event -> ((ChatEvent.ChatCompletedEvent) event).getChatResponse())
                    .collect().last()
                    .await().indefinitely();
        } catch (RuntimeException e) {
            LOG.error("The review failed", e);
            return 1;
        } finally {
            progress.cancel();
        }

        long end = System.nanoTime();
        long first = firstToken.get() == 0 ? end : firstToken.get();
        System.out.printf("%n%n--- Review completed in %.1f s ---%n", seconds(start, end));
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage != null && usage.inputTokenCount() != null && usage.outputTokenCount() != null) {
            // Times, not a rate: a provider that does not stream the reasoning prints nothing until the
            // review starts, so the first output says nothing about when generation began.
            LOG.infof("Prompt: %,d tokens. Generated: %,d tokens (reasoning and review). First output after"
                    + " %.1f s, review completed in %.1f s", usage.inputTokenCount(), usage.outputTokenCount(),
                    seconds(start, first), seconds(start, end));
        }
        return 0;
    }

    private String description(String body) {
        if (body == null || body.isBlank()) {
            return "(none)";
        }
        int max = config.maxDescriptionChars();
        return body.length() <= max ? body : body.substring(0, max) + "\n[truncated]";
    }

    private static String coverage(DiffFormatter.Result formatted) {
        if (formatted.skipped().isEmpty() && formatted.overBudget().isEmpty()) {
            return "The diff below is complete.";
        }
        StringBuilder note = new StringBuilder("The diff below is partial.");
        if (!formatted.overBudget().isEmpty()) {
            note.append(" Not included, too large to fit: ").append(String.join(", ", formatted.overBudget())).append('.');
        }
        if (!formatted.skipped().isEmpty()) {
            note.append(" Not included, lock/generated/binary: ").append(String.join(", ", formatted.skipped()))
                    .append('.');
        }
        return note.toString();
    }

    private static void printList(String label, List<String> paths) {
        if (!paths.isEmpty()) {
            System.out.printf("%s: %s%n", label, String.join(", ", paths));
        }
    }

    private static double seconds(long from, long to) {
        return (to - from) / 1e9;
    }

    private static String describe(PullRequestLink pr, int status) {
        return switch (status) {
            case 404 -> "Pull request " + pr + " not found. For a private repository, set GITHUB_TOKEN.";
            case 401, 403, 429 -> "GitHub refused the request (HTTP " + status
                    + "). If you hit the rate limit, set GITHUB_TOKEN.";
            case 406, 422 -> "GitHub cannot produce a diff for " + pr + " (HTTP " + status + "); it is probably too large.";
            default -> "GitHub returned HTTP " + status + " for " + pr + ".";
        };
    }
}
