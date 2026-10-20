package leasegate.gitgate.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Asks GitHub who a token belongs to and what it may do, before it is stored -- so a pasted token
/// that is wrong, expired or for another account is caught at once, and a token that can delete
/// repositories is named as such. Classic tokens list their scopes in X-OAuth-Scopes; a
/// fine-grained token lists none, and its permissions are whatever was chosen when it was made.
record GitHubCheck(boolean ok, String login, String scopes, String problem) {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final ObjectMapper M = new ObjectMapper();

    static GitHubCheck of(String token) {
        try {
            var req = HttpRequest.newBuilder(URI.create("https://api.github.com/user")).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json").GET().build();
            var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return new GitHubCheck(false, null, null, "GitHub answered " + res.statusCode() + (res.statusCode() == 401 ? " (the token is wrong or expired)" : ""));
            var login = M.readTree(res.body()).path("login").asText();
            return new GitHubCheck(true, login, res.headers().firstValue("x-oauth-scopes").orElse(null), null);
        } catch (Exception e) { return new GitHubCheck(false, null, null, "could not reach GitHub: " + e.getMessage()); }
    }

    boolean canDeleteRepos() { return scopes != null && scopes.contains("delete_repo"); }

    String describe() {
        if (!ok) return problem;
        return "belongs to " + login + (scopes == null ? " (fine-grained: its permissions are what was chosen on GitHub)"
            : " · scopes: " + (scopes.isBlank() ? "none" : scopes)) + (canDeleteRepos() ? " · CAN DELETE REPOSITORIES" : "");
    }
}
