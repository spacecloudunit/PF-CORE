/*
 * Copyright 2004 - 2008 Christian Sprajc. All rights reserved.
 *
 * This file is part of PowerFolder.
 *
 * PowerFolder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * PowerFolder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PowerFolder. If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id$
 */
package de.dal33t.powerfolder.ui.widget;

import de.dal33t.powerfolder.ui.Icons;
import de.dal33t.powerfolder.util.BrowserLauncher;
import de.dal33t.powerfolder.util.StringUtils;
import de.dal33t.powerfolder.util.ui.ColorUtil;
import de.dal33t.powerfolder.util.ui.SimpleComponentFactory;
import de.dal33t.powerfolder.util.ui.UIUtil;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PreferencesEntry;

import javax.swing.*;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jgoodies.forms.factories.Borders;

/**
 * A Label which opens a given link by click it
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.4 $
 */
public class LinkLabel extends PFComponent {

    private static final Logger log = Logger.getLogger(LinkLabel.class
        .getName());
    private String url;
    private JLabel uiComponent;
    private String text;
    private volatile boolean mouseOver;

    public LinkLabel(Controller controller, String text, String url) {
        super(controller);
        this.text = text;
        this.url = url;
        uiComponent = new JLabel();

        setText();

        uiComponent.addMouseListener(new MyMouseAdapter());

        uiComponent.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // FIXME This is a hack because of "Fusch!"
        uiComponent.setBorder(Borders.createEmptyBorder("0, 1px, 0, 0"));
    }

    public void setTextAndURL(String text, String url) {
        this.text = text;
        this.url = url;
        setText();
    }

    public void setForeground(Color c) {
        uiComponent.setForeground(c);
    }

    public void setIcon(Icon icon) {
        uiComponent.setIcon(icon);
    }

    public void setFontSize(int fontSize) {
        SimpleComponentFactory.setFont(uiComponent, fontSize, uiComponent
            .getFont().getStyle());
    }

    public void setFontStyle(int style) {
        SimpleComponentFactory.setFont(uiComponent, uiComponent.getFont()
            .getSize(), style);
    }

    public void convertToBigLabel() {
        uiComponent.setIcon(Icons.getIconById(Icons.ARROW_RIGHT));
        setFontSize(UIUtil.MED_FONT_SIZE);
    }

    public JLabel getUIComponent() {
        return uiComponent;
    }

    private void setText() {

        if (mouseOver
            || PreferencesEntry.UNDERLINE_LINKS
                .getValueBoolean(getController()))
        {
            Color color = ColorUtil.getTextForegroundColor();
            String rgb = ColorUtil.getRgbForColor(color);
            uiComponent.setText("<html><font color=\"" + rgb + "\"><a href=\""
                + url + "\">" + text + "</a></font></html>");
        } else {
            uiComponent.setText(text);
        }
    }

    public void setToolTipText(String tips) {
        uiComponent.setToolTipText(tips);
    }

    private class MyMouseAdapter extends MouseAdapter {
        public void mouseClicked(MouseEvent e) {
            if (StringUtils.isBlank(url)) {
                return;
            }
            try {
                BrowserLauncher.openURL(url);
            } catch (IOException e1) {
                log.log(Level.SEVERE, "IOException", e1);
            }
        }

        public void mouseEntered(MouseEvent e) {
            mouseOver = true;
            setText();
        }

        public void mouseExited(MouseEvent e) {
            mouseOver = false;
            setText();
        }
    }

}