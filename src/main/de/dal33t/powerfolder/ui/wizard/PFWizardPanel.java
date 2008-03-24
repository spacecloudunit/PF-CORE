/* $Id: PFWizardPanel.java,v 1.4 2005/11/20 00:22:10 totmacherr Exp $
 */
package de.dal33t.powerfolder.ui.wizard;

import java.awt.Color;
import java.util.List;

import javax.swing.JComponent;

import jwf.WizardPanel;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.ui.widget.AntialiasedLabel;
import de.dal33t.powerfolder.util.Help;
import de.dal33t.powerfolder.util.Logger;
import de.dal33t.powerfolder.util.ui.SimpleComponentFactory;

/**
 * Base class for wizard panels
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.4 $
 */
public abstract class PFWizardPanel extends WizardPanel {
    private Controller controller;
    private Logger log;

    /**
     * Initalization
     * 
     * @param controller
     *            the controller
     */
    public PFWizardPanel(Controller controller) {
        super();
        if (controller == null) {
            throw new NullPointerException("Controller is null");
        }
        this.controller = controller;
        // Set white background for all folder panels
        setBackground(Color.WHITE);
    }

    // We do not need validiation *********************************************

    public boolean validateNext(List list) {
        return true;
    }

    public boolean validateFinish(List list) {
        return true;
    }

    /**
     * We have help. Open docs in browser
     * 
     * @return true
     */
    public boolean hasHelp() {
        return true;
    }

    /** Always open online docu */
    public void help() {
        Help.openHelp("documentation.html");
    }

    // Helper code ************************************************************

    /**
     * Sets the correct colors to the component
     * 
     * @param comp
     */
    protected void setColors(JComponent comp) {
        comp.setBackground(Color.WHITE);
    }

    /**
     * @param text
     * @return  a label which can be used as title. Has smoothed font
     */
    protected JComponent createTitleLabel(String text) {
        AntialiasedLabel label = new AntialiasedLabel(text);
        SimpleComponentFactory.setFontSize(label, PFWizard.HEADER_FONT_SIZE);
        return label;
    }

    // General ****************************************************************

    /**
     * @return the controller
     */
    protected Controller getController() {
        return controller;
    }

    /**
     * @return a logger for this panel
     */
    protected Logger log() {
        if (log == null) {
            log = Logger.getLogger(this);
        }
        return log;
    }
}