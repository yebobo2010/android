/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.npw.assetstudio;

import com.android.SdkConstants;
import com.android.utils.CharSequences;
import com.android.utils.XmlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

import static com.android.SdkConstants.ANDROID_URI;

/**
 * Methods for manipulating vector drawables.
 */
public class VectorDrawableTransformer {
  /** Do not instantiate. All methods are static. */
  private VectorDrawableTransformer() {}

  /**
   * Transforms a vector drawable to fit in a rectangle with the {@code targetSize} dimensions.
   * Conceptually, the transformation includes of the following steps:
   * <ul>
   *   <li>The drawable is resized and centered in a rectangle of the target size</li>
   *   <li>If {@code clipRectangle} is not null, the drawable is clipped, resized and re-centered again</li>
   *   <li>The drawable is scaled according to {@code scaleFactor}</li>
   *   <li>The drawable is either padded or clipped to fit into the target rectangle</li>
   * </ul>
   *
   * @param originalDrawable the original drawable, preserved intact by the method
   * @param targetSize the size of the target rectangle
   * @param scaleFactor a scale factor to apply
   * @param clipRectangle an optional clip rectangle in coordinates expressed as fraction of
   *     the {@code targetSize}
   * @return the transformed drawable; may be the same as the original if no transformation was
   *     required, or if the drawable is not a vector one
   */
  @NotNull
  public static String resizeAndCenter(@NotNull String originalDrawable, @NotNull Dimension targetSize, double scaleFactor,
                                       @Nullable Rectangle2D clipRectangle) {
    KXmlParser parser = new KXmlParser();

    try {
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      parser.setInput(CharSequences.getReader(originalDrawable, true));
      int startLine = 1;
      int startColumn = 1;
      int token;
      while ((token = parser.nextToken()) != XmlPullParser.END_DOCUMENT && token != XmlPullParser.START_TAG) {
        startLine = parser.getLineNumber();
        startColumn = parser.getColumnNumber();
      }
      // Skip to the first tag.
      if (parser.getEventType() != XmlPullParser.START_TAG || !"vector".equals(parser.getName()) || parser.getPrefix() != null) {
        return originalDrawable; // Not a vector drawable.
      }

      double targetWidth = targetSize.getWidth();
      double targetHeight = targetSize.getHeight();
      double width = targetWidth;
      double height = targetHeight;
      String widthValue = parser.getAttributeValue(ANDROID_URI, "width");
      if (widthValue != null) {
        String suffix = getSuffix(widthValue);
        width = getDoubleAttributeValue(parser, ANDROID_URI, "width", suffix);
        height = getDoubleAttributeValue(parser, ANDROID_URI, "height", suffix);
        //noinspection FloatingPointEquality -- safe in this context since all integer values are representable as double.
        if (suffix.equals("dp") && width == targetWidth && height == targetHeight && scaleFactor == 1 && clipRectangle == null) {
          return originalDrawable; // No transformation is needed.
        }
        if (Double.isNaN(width) || Double.isNaN(height)) {
          width = targetWidth;
          height = targetHeight;
        }
      }

      double originalViewportWidth = getDoubleAttributeValue(parser, ANDROID_URI, "viewportWidth", "");
      double originalViewportHeight = getDoubleAttributeValue(parser, ANDROID_URI, "viewportHeight", "");
      if (Double.isNaN(originalViewportWidth) || Double.isNaN(originalViewportHeight)) {
        originalViewportWidth = width;
        originalViewportHeight = height;
      }
      double viewportWidth = originalViewportWidth;
      double viewportHeight = originalViewportHeight;
      // Components of the translation vector in viewport coordinates.
      double x = 0;
      double y = 0;
      double ratio = targetWidth * height / (targetHeight * width);
      // Adjust viewport to compensate for the difference between the original and the target aspect ratio.
      if (ratio > 1) {
        viewportWidth *= ratio;
      }
      else if (ratio < 1) {
        viewportHeight /= ratio;
      }

      // Apply scaleFactor.
      viewportWidth /= scaleFactor;
      viewportHeight /= scaleFactor;

      if (clipRectangle != null) {
        // Adjust viewport.
        double s = Math.max(clipRectangle.getWidth(), clipRectangle.getHeight());
        viewportWidth *= s;
        viewportHeight *= s;
        // Re-center the image relative to the clip rectangle.
        x = (0.5 - clipRectangle.getCenterX()) * viewportWidth;
        y = (0.5 - clipRectangle.getCenterY()) * viewportHeight;
      }

      // Compensate for the shift of the viewport center due to scaling.
      x += (viewportWidth - originalViewportWidth) / 2;
      y += (viewportHeight - originalViewportHeight) / 2;

      StringBuilder result = new StringBuilder(originalDrawable.length() + originalDrawable.length() / 8);

      Indenter indenter = new Indenter(originalDrawable);
      // Copy contents before the first element.
      indenter.copy(1, 1, startLine, startColumn, "", result);
      String lineDelimiter = detectLineDelimiter(originalDrawable);
      // Output the "vector" element with the xmlns:android attribute.
      result.append(String.format("<vector %s:%s=\"%s\"", SdkConstants.XMLNS, SdkConstants.ANDROID_NS_NAME, SdkConstants.NS_RESOURCES));
      // Copy remaining namespace attributes.
      for (int i = 0; i < parser.getNamespaceCount(1); i++) {
        String prefix = parser.getNamespacePrefix(i);
        String uri = parser.getNamespaceUri(i);
        if (!SdkConstants.ANDROID_NS_NAME.equals(prefix) || !SdkConstants.NS_RESOURCES.equals(uri)) {
          result.append(String.format("%s        %s:%s=\"%s\"", lineDelimiter, SdkConstants.XMLNS, prefix, uri));
        }
      }

      result.append(String.format(
          "%s        android:width=\"%sdp\"" +
          "%s        android:height=\"%sdp\"" +
          "%s        android:viewportWidth=\"%s\"" +
          "%s        android:viewportHeight=\"%s\"",
          lineDelimiter, XmlUtils.formatFloatAttribute(targetWidth),
          lineDelimiter, XmlUtils.formatFloatAttribute(targetHeight),
          lineDelimiter, XmlUtils.formatFloatAttribute(viewportWidth),
          lineDelimiter, XmlUtils.formatFloatAttribute(viewportHeight)));

      // Copy remaining attributes.
      for (int i = 0; i < parser.getAttributeCount(); i++) {
        String prefix = parser.getAttributePrefix(i);
        String name = parser.getAttributeName(i);
        if (!SdkConstants.ANDROID_NS_NAME.equals(prefix) ||
            (!"width".equals(name) && !"height".equals(name) && !"viewportWidth".equals(name) && !"viewportHeight".equals(name))) {
          if (prefix != null) {
            name = prefix + ':' + name;
          }
          result.append(String.format("%s        %s=\"%s\"", lineDelimiter, name, parser.getAttributeValue(i)));
        }
      }
      result.append('>');

      String indent = "";
      String translateX = isSignificantlyDifferentFromZero(x / viewportWidth) ? XmlUtils.formatFloatAttribute(x) : null;
      String translateY = isSignificantlyDifferentFromZero(y / viewportHeight) ? XmlUtils.formatFloatAttribute(y) : null;
      if (translateX != null || translateY != null) {
        // Wrap the contents of the drawable into a translation group.
        result.append(lineDelimiter);
        result.append("    <group");
        String delimiter = " ";
        if (translateX != null) {
          result.append(String.format("%sandroid:translateX=\"%s\"", delimiter, translateX));
          delimiter = lineDelimiter + "            ";
        }
        if (translateY != null) {
          result.append(String.format("%sandroid:translateY=\"%s\"", delimiter, translateY));
        }
        result.append('>');
        indent = "    ";
      }

      // Copy the contents before the </vector> tag.
      startLine = parser.getLineNumber();
      startColumn = parser.getColumnNumber();
      while ((token = parser.nextToken()) != XmlPullParser.END_DOCUMENT && token != XmlPullParser.END_TAG || parser.getDepth() > 1) {
        int endLineNumber = parser.getLineNumber();
        int endColumnNumber = parser.getColumnNumber();
        indenter.copy(startLine, startColumn, endLineNumber, endColumnNumber, token == XmlPullParser.CDSECT ? "" : indent, result);
        startLine = endLineNumber;
        startColumn = endColumnNumber;
      }
      if (startColumn != 1) {
        result.append(lineDelimiter);
      }
      if (translateX != null || translateY != null) {
        result.append(String.format("    </group>%s", lineDelimiter));
      }
      // Copy the closing </vector> tag and the remainder of the document.
      while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
        int endLineNumber = parser.getLineNumber();
        int endColumnNumber = parser.getColumnNumber();
        indenter.copy(startLine, startColumn, endLineNumber, endColumnNumber, "", result);
        startLine = endLineNumber;
        startColumn = endColumnNumber;
      }

      return result.toString();
    }
    catch (XmlPullParserException | IOException e) {
      return originalDrawable;  // Ignore and return the original drawable.
    }
  }

  private static String detectLineDelimiter(CharSequence str) {
    int pos = CharSequences.indexOf(str, '\n');
    if (pos > 0 && str.charAt(pos - 1) == '\r') {
      return "\r\n";
    }
    return "\n";
  }

  private static double getDoubleAttributeValue(@NotNull KXmlParser parser, @NotNull String namespaceUri, @NotNull String attributeName,
                                                @NotNull String expectedSuffix) {
    String value = parser.getAttributeValue(namespaceUri, attributeName);
    if (value == null || !value.endsWith(expectedSuffix)) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(value.substring(0, value.length() - expectedSuffix.length()));
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  @NotNull
  private static String getSuffix(@NotNull String value) {
    int i = value.length();
    while (--i >= 0) {
      if (Character.isDigit(value.charAt(i))) {
        break;
      }
    }
    ++i;
    return value.substring(i);
  }

  private static boolean isSignificantlyDifferentFromZero(double value) {
    return Math.abs(value) >= 1.e-6;
  }

  private static class Indenter {
    private int myLine;
    private int myColumn;
    private int myOffset;
    private final CharSequence myText;

    Indenter(CharSequence text) {
      myText = text;
      myLine = 1;
      myColumn = 1;
    }

    void copy(int fromLine, int fromColumn, int toLine, int toColumn, String indent, StringBuilder out) {
      if (myLine != fromLine) {
        if (myLine > fromLine) {
          myLine = 1;
          myColumn = 1;
          myOffset = 0;
        }
        while (myLine < fromLine) {
          char c = myText.charAt(myOffset);
          if (c == '\n') {
            myLine++;
            myColumn = 1;
          } else {
            if (myLine != 1 || myColumn != 1 || c != '\uFEFF') {  // Byte order mark doesn't occupy a column.
              myColumn++;
            }
          }
          myOffset++;
        }
      }
      myOffset += fromColumn - myColumn;
      myColumn = fromColumn;
      while (myLine < toLine || myLine == toLine && myColumn < toColumn) {
        char c = myText.charAt(myOffset);
        if (c == '\n') {
          myLine++;
          myColumn = 1;
        } else {
          if (myLine != 1 || myColumn != 1 || c != '\uFEFF') {  // Byte order mark doesn't occupy a column.
            if (myColumn == 1) {
              out.append(indent);
            }
            myColumn++;
          }
        }
        myOffset++;
        out.append(c);
      }
    }
  }
}
