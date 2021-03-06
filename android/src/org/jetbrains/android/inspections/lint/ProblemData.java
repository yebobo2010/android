package org.jetbrains.android.inspections.lint;

import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.LintFix;
import com.android.tools.lint.detector.api.Severity;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Eugene.Kudelevsky
 */
public class ProblemData {
  private final Issue myIssue;
  private final String myMessage;
  private final TextRange myTextRange;
  private final Severity myConfiguredSeverity;
  private LintFix myQuickfixData;

  public ProblemData(@NotNull Issue issue, @NotNull String message, @NotNull TextRange textRange, @Nullable Severity configuredSeverity,
                     @Nullable LintFix quickfixData) {
    myIssue = issue;
    myTextRange = textRange;
    myMessage = message;
    myConfiguredSeverity = configuredSeverity;
    myQuickfixData = quickfixData;
  }

  @NotNull
  public Issue getIssue() {
    return myIssue;
  }

  @NotNull
  public TextRange getTextRange() {
    return myTextRange;
  }

  @NotNull
  public String getMessage() {
    return myMessage;
  }

  @Nullable
  public Severity getConfiguredSeverity() {
    return myConfiguredSeverity;
  }

  @Nullable
  public LintFix getQuickfixData() {
    return myQuickfixData;
  }
}
