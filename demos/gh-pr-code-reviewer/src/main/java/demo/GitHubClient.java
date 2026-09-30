package demo;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestPath;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The two GitHub REST API calls the reviewer needs: a pull request and its unified diff.
 * <p>
 * Public repositories need no credentials. Setting {@code GITHUB_TOKEN} gives access to private ones
 * and lifts the unauthenticated rate limit.
 */
@RegisterRestClient(configKey = "github")
@Path("/repos/{owner}/{repo}/pulls/{number}")
@ClientHeaderParam(name = "X-GitHub-Api-Version", value = "2022-11-28")
@ClientHeaderParam(name = "Authorization", value = "{authorization}", required = false)
public interface GitHubClient {

    @GET
    @Produces("application/vnd.github+json")
    PullRequest pullRequest(@RestPath String owner, @RestPath String repo, @RestPath int number);

    @GET
    @Produces("application/vnd.github.diff")
    String diff(@RestPath String owner, @RestPath String repo, @RestPath int number);

    /** {@code Bearer <token>} when {@code github.token} is set; no header otherwise. */
    default String authorization() {
        return ConfigProvider.getConfig().getOptionalValue("github.token", String.class)
                .filter(token -> !token.isBlank())
                .map(token -> "Bearer " + token)
                .orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequest(
            String title,
            String body,
            User user,
            Branch base,
            Branch head,
            int additions,
            int deletions,
            @JsonProperty("changed_files") int changedFiles,
            @JsonProperty("html_url") String htmlUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(String ref, String label) {
    }
}
