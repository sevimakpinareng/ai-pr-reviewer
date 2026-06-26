package com.example.prreviewer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.service.GithubApiClient.InlineComment;

/**
 * Turns a {@link ReviewResult} into one batched GitHub pull-request review.
 * <p>
 * Findings whose file+line land on a commentable RIGHT-side diff line become
 * inline comments; everything else (no line, off-diff line, or unknown file) is
 * listed in the review body so no finding is ever dropped. Exactly one review is
 * posted per call.
 */
@Service
public class GithubReviewPublisher {

    private static final Logger log = LoggerFactory.getLogger(GithubReviewPublisher.class);

    private static final String SIDE_RIGHT = "RIGHT";
    private static final String NO_ISSUES_BODY = "Önemli bir sorun bulunamadı.";

    private final GithubApiClient githubApiClient;
    private final DiffLineMapper diffLineMapper;
    private final boolean postEmptyReview;

    public GithubReviewPublisher(GithubApiClient githubApiClient,
                                 DiffLineMapper diffLineMapper,
                                 @Value("${app.review.post-empty-review:true}") boolean postEmptyReview) {
        this.githubApiClient = githubApiClient;
        this.diffLineMapper = diffLineMapper;
        this.postEmptyReview = postEmptyReview;
    }

    /**
     * Posts the review to GitHub.
     *
     * @return the GitHub review id, or {@code null} if nothing was posted
     */
    public Long publish(long installationId, String owner, String repo, int pullNumber,
                        String commitSha, ReviewResult result, String rawDiff) {
        List<ReviewFinding> findings = result.findings() == null ? List.of() : result.findings();

        if (findings.isEmpty()) {
            if (!postEmptyReview) {
                log.info("No findings and post-empty-review disabled; not posting to GitHub");
                return null;
            }
            return githubApiClient.submitReview(installationId, owner, repo, pullNumber,
                    commitSha, NO_ISSUES_BODY, List.of());
        }

        Map<String, Set<Integer>> commentable = diffLineMapper.commentableLines(rawDiff);

        List<InlineComment> inlineComments = new ArrayList<>();
        List<ReviewFinding> summaryFindings = new ArrayList<>();
        for (ReviewFinding finding : findings) {
            if (isInlineable(finding, commentable)) {
                inlineComments.add(new InlineComment(
                        finding.filePath(), finding.line(), SIDE_RIGHT, formatInline(finding)));
            } else {
                summaryFindings.add(finding);
            }
        }

        String body = buildBody(result.summary(), summaryFindings);
        log.info("Posting review to {}/{} #{}: {} inline, {} in summary",
                owner, repo, pullNumber, inlineComments.size(), summaryFindings.size());
        return githubApiClient.submitReview(installationId, owner, repo, pullNumber,
                commitSha, body, inlineComments);
    }

    private static boolean isInlineable(ReviewFinding finding, Map<String, Set<Integer>> commentable) {
        return finding.filePath() != null
                && finding.line() != null
                && commentable.getOrDefault(finding.filePath(), Set.of()).contains(finding.line());
    }

    private static String formatInline(ReviewFinding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(finding.severity()).append("] ").append(nullToEmpty(finding.title())).append("**");
        if (finding.detail() != null && !finding.detail().isBlank()) {
            sb.append("\n\n").append(finding.detail().strip());
        }
        return sb.toString();
    }

    private static String buildBody(String summary, List<ReviewFinding> summaryFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("### AI Review\n\n");
        sb.append(summary == null || summary.isBlank() ? "Review complete." : summary.strip());

        if (!summaryFindings.isEmpty()) {
            sb.append("\n\n**Additional findings (not tied to a diff line):**\n");
            for (ReviewFinding finding : summaryFindings) {
                sb.append("- [").append(finding.severity()).append("] `")
                        .append(nullToEmpty(finding.filePath()));
                if (finding.line() != null) {
                    sb.append(':').append(finding.line());
                }
                sb.append("` — ").append(nullToEmpty(finding.title()));
                if (finding.detail() != null && !finding.detail().isBlank()) {
                    sb.append(" — ").append(finding.detail().strip());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
