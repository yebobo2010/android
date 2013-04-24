/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.jps.parser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Error8Parser extends ProblemParser {
  /**
   * 2-line aapt error
   * <pre>
   * ERROR: Invalid configuration: foo
   *                               ^^^
   * </pre>
   * There's no need to parse the 2nd line.
   */
  private static final Pattern MSG_PATTERN = Pattern.compile("^Invalid configuration: (.+)$");

  @NotNull private final ProblemMessageFactory myMessagefactory;

  Error8Parser(@NotNull AaptProblemMessageFactory messageFactory) {
    myMessagefactory = messageFactory;
  }

  @NotNull
  @Override
  ParsingResult parse(@NotNull String line) {
    Matcher m = MSG_PATTERN.matcher(line);
    if (!m.matches()) {
      return ParsingResult.NO_MATCH;
    }
    String badConfig = m.group(1);
    String msgText = String.format("APK Configuration filter '%1$s' is invalid", badConfig);
    // skip the next line
    myMessagefactory.getOutputReader().skipNextLine();
    CompilerMessage msg = myMessagefactory.createErrorMessage(msgText, null, null);
    return new ParsingResult(msg);
  }
}
