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
package com.android.tools.idea.editors.layoutInspector.ui;

import com.android.layoutinspector.model.ViewNode;
import com.android.tools.idea.editors.strings.FontUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.android.dom.AndroidDomElementDescriptorProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class ViewNodeTreeRenderer extends ColoredTreeCellRenderer {
  private static Icon DEFAULT_VIEW_ICON = AndroidDomElementDescriptorProvider.getIconForViewTag("View");

  @Override
  public void customizeCellRenderer(JTree tree, Object nodeValue, boolean selected,
                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
    if (!(nodeValue instanceof ViewNode)) {
      return;
    }

    ViewNode node = (ViewNode)nodeValue;
    String[] name = node.name.split("\\.");
    String elementName = name[name.length - 1];
    append(elementName + " ",
           node.isDrawn() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
    setIcon(findIconForNode(elementName));
    if (node.displayInfo.contentDesc != null) {
      Font currentFont = getFont();
      Font f = FontUtil.getFontAbleToDisplay(node.displayInfo.contentDesc, currentFont);
      if (f != null && f != currentFont) {
        setFont(f);
      }
      append(node.displayInfo.contentDesc,
             node.isDrawn() ? SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES : SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES);
    }
  }


  /**
   * Determine the icon to use given a node. First try full element name.
   * If there is no matching icon, then try a subset of the name (i.e. map AppCompatEditText to EditText)
   *
   * @param elementName the elementName name we want to find icon for
   * @return Icon for the node
   */
  @Nullable
  private Icon findIconForNode(@NotNull String elementName) {
    Icon icon = null;
    String[] words = elementName.split("(?=\\p{Upper})");

    int index = 0;
    StringBuilder builder;
    while (icon == null && index < words.length) {
      builder = new StringBuilder();
      for (int i = index; i < words.length; i++) {
        builder.append(words[i]);
      }
      icon = AndroidDomElementDescriptorProvider.getIconForViewTag(builder.toString());
      index++;
    }

    return icon != null ? icon : DEFAULT_VIEW_ICON;
  }
}
