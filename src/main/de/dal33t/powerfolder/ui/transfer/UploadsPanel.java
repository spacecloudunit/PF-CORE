/* $Id: UploadsPanel.java,v 1.4 2006/04/15 04:23:51 totmacherr Exp $
 */
package de.dal33t.powerfolder.ui.transfer;

import com.jgoodies.forms.builder.ButtonBarBuilder;
import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.transfer.Upload;
import de.dal33t.powerfolder.ui.QuickInfoPanel;
import de.dal33t.powerfolder.ui.action.HasDetailsPanel;
import de.dal33t.powerfolder.ui.action.ShowHideFileDetailsAction;
import de.dal33t.powerfolder.ui.builder.ContentPanelBuilder;
import de.dal33t.powerfolder.ui.dialog.FileDetailsPanel;
import de.dal33t.powerfolder.util.PFUIPanel;
import de.dal33t.powerfolder.util.Translation;
import de.dal33t.powerfolder.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Contains all information about uploads
 * 
 * @version $Revision: 1.4 $
 */
public class UploadsPanel extends PFUIPanel implements HasDetailsPanel {
    private JComponent panel;

    private QuickInfoPanel quickInfo;
    private UploadsTable table;
    private UploadsTableModel tableModel;
    private JScrollPane tablePane;
    private JComponent toolbar;
    private JToggleButton showHideFileDetailsButton;

    // The actions
    private FileDetailsPanel fileDetailsPanel;
    private JComponent fileDetailsPanelComp;

    // private Action clearCompletedAction;

    public UploadsPanel(Controller controller) {
        super(controller);
    }

    // UI Building ************************************************************

    /**
     * @return (and builds layzily) the ui component of this panel
     */
    public Component getUIComponent() {
        if (panel == null) {
            initComponents();
            ContentPanelBuilder builder = new ContentPanelBuilder();
            builder.setQuickInfo(quickInfo.getUIComponent());
            builder.setToolbar(toolbar);
            builder.setContent(createContentPanel());
            panel = builder.getPanel();
        }
        return panel;
    }

    private JComponent createContentPanel() {
        FormLayout layout = new FormLayout("fill:pref:grow",
            "fill:0:grow, pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();
        builder.add(tablePane, cc.xy(1, 1));
        builder.add(fileDetailsPanelComp, cc.xy(1, 2));
        return builder.getPanel();
    }

    public String getTitle() {
        return Translation.getTranslation("general.uploads");
    }

    private JComponent getFileDetailsPanelComp() {
        if (fileDetailsPanelComp == null) {
            fileDetailsPanelComp = createFileDetailsPanel();
        }
        return fileDetailsPanelComp;
    }

    /*
     * public Action getShowHideFileDetailsAction(){
     * if(showHideFileDetailsAction == null){ showHideFileDetailsAction = }
     * return showHideFileDetailsAction; }
     */

    private void initComponents() {
        quickInfo = new UploadsQuickInfoPanel(getController());
        // Uploads table
        table = new UploadsTable(getUIController().getTransferManagerModel());
        table.getTableHeader().addMouseListener(
            new TableHeaderMouseListener());
        tableModel = (UploadsTableModel) table.getModel();
        tablePane = new JScrollPane(table);
        // Whitestrip
        UIUtil.whiteStripTable(table);
        UIUtil.removeBorder(tablePane);
        UIUtil.setZeroHeight(tablePane);

        // The file/upload info
        fileDetailsPanelComp = getFileDetailsPanelComp();
        fileDetailsPanelComp.setVisible(false);

        // Initalize actions
        // abortDownloadsAction = new AbortDownloadAction();
        // abortDownloadsAction.setEnabled(false);
        // startDownloadsAction = new StartDownloadsAction();
        // startDownloadsAction.setEnabled(false);
        Action showHideFileDetailsAction = new ShowHideFileDetailsAction(
                this, getController());
        showHideFileDetailsButton = new JToggleButton(showHideFileDetailsAction);

        // clearCompletedAction = new ClearCompletedAction();

        // Create toolbar
        toolbar = createToolBar();

        // Add mouselisteners to table
        // table.addMouseListener(new DoubleClickAction(startDownloadsAction));
        // table.addMouseListener(new PopupMenuOpener(createPopupMenu()));

        // Listener on table selections
        table.getSelectionModel().addListSelectionListener(
            new ListSelectionListener() {
                public void valueChanged(ListSelectionEvent e) {
                    if (!e.getValueIsAdjusting()) {
                        // Update actions
                        // updateActions();
                        int index = table.getSelectionModel()
                            .getLeadSelectionIndex();
                        if (index >= 0 && index < tableModel.getRowCount()) {
                            Upload ul = tableModel.getUploadAtRow(index);
                            if (ul != null) {// null if upload removed in
                                // meantime
                                fileDetailsPanel.setFile(ul.getFile());
                            }
                        }
                    }
                }
            });

        // setup inital actions state
        // updateActions();
    }

    /**
     * @return the file panel
     */
    private JComponent createFileDetailsPanel() {
        fileDetailsPanel = new FileDetailsPanel(getController());

        FormLayout layout = new FormLayout("fill:pref:grow",
            "pref, 3dlu, pref, fill:pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();
        builder.addSeparator(null, cc.xy(1, 1));
        builder.addSeparator(null, cc.xy(1, 3));
        builder.add(fileDetailsPanel.getEmbeddedPanel(), cc.xy(1, 4));
        return builder.getPanel();
    }

    /**
     * @return the toolbar
     */
    private JComponent createToolBar() {
        // Create toolbar
        ButtonBarBuilder bar = ButtonBarBuilder.createLeftToRightBuilder();
        // bar.addGridded(new JButton(startDownloadsAction));
        // bar.addRelatedGap();
        // bar.addGridded(new JButton(abortDownloadsAction));
        // bar.addUnrelatedGap();
        bar.addGridded(showHideFileDetailsButton);
        // bar.addRelatedGap();
        // bar.addGridded(new JButton(clearCompletedAction));
        JPanel barPanel = bar.getPanel();
        barPanel.setBorder(Borders.DLU4_BORDER);

        return barPanel;
    }

    public void toggeDetails() {
        // Ensure the component is created.
        JComponent comp = getFileDetailsPanelComp();
        comp.setVisible(!comp.isVisible());
        showHideFileDetailsButton.setSelected(comp.isVisible());
    }


    /**
     * Listener on table header, takes care about the sorting of table
     *
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     */
    private class TableHeaderMouseListener extends MouseAdapter {
        public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                JTableHeader tableHeader = (JTableHeader) e.getSource();
                int columnNo = tableHeader.columnAtPoint(e.getPoint());
                TableColumn column = tableHeader.getColumnModel().getColumn(
                    columnNo);
                int modelColumnNo = column.getModelIndex();
                TableModel model = tableHeader.getTable().getModel();
                if (model instanceof UploadsTableModel) {
                    UploadsTableModel uploadsTableModel = (UploadsTableModel) model;
                    boolean freshSorted = uploadsTableModel.sortBy(modelColumnNo);
                    if (!freshSorted) {
                        // reverse list
                        uploadsTableModel.reverseList();
                    }
                }
            }
        }
    }


    /**
     * Creates the uploads popup menu
     */
    // private JPopupMenu createPopupMenu() {
    // JPopupMenu popupMenu = SimpleComponentFactory.createPopupMenu();
    // popupMenu.add(startDownloadsAction);
    // popupMenu.add(abortDownloadsAction);
    // popupMenu.addSeparator();
    // popupMenu.add(clearCompletedAction);
    // return popupMenu;
    // }
    // Helper methods *********************************************************
    /**
     * Updates all action states (enabled/disabled)
     */
    // private void updateActions() {
    // abortDownloadsAction.setEnabled(false);
    // startDownloadsAction.setEnabled(false);
    // int[] rows = table.getSelectedRows();
    // if (rows == null || rows.length <= 0) {
    // return;
    // }
    // }
    // for (int i = 0; i < rows.length; i++) {
    // Download download = tableModel.getDownloadAtRow(rows[i]);
    // if (download.isCompleted()) {
    // startDownloadsAction.setEnabled(true);
    // }
    // if (!download.isCompleted()) {
    // abortDownloadsAction.setEnabled(true);
    // }
    // }
    // }
    // Inner classes **********************************************************
    /**
     * Starts the selected downloads
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     * @version $Revision: 1.4 $
     */
    // private class StartDownloadsAction extends BaseAction {
    // public StartDownloadsAction() {
    // super("startdownloads", UploadsPanel.this.getController());
    // }
    // public void actionPerformed(ActionEvent e) {
    // int[] rows = table.getSelectedRows();
    // if (rows == null || rows.length <= 0) {
    // return;
    // }
    // Download[] selected = new Download[rows.length];
    // for (int i = 0; i < rows.length; i++) {
    // selected[i] = tableModel.getDownloadAtRow(rows[i]);
    // }
    // Abort it two steps, because .abort causes model to change
    // for (int i = 0; i < selected.length; i++) {
    // File file = selected[i].getFile().getDiskFile(
    // UploadsPanel.this.getController().getFolderRepository());
    // if (file != null && file.exists()) {
    // try {
    // Util.executeFile(file);
    // } catch (IOException ex) {
    // log().error(ex);
    // }
    // }
    // }
    // }
    // }
    /**
     * Aborts the selected downloads
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     * @version $Revision: 1.4 $
     */
    // private class AbortDownloadAction extends BaseAction {
    // public AbortDownloadAction() {
    // super("abortdownload", UploadsPanel.this.getController());
    // }
    // public void actionPerformed(ActionEvent e) {
    // int[] rows = table.getSelectedRows();
    // if (rows == null || rows.length <= 0) {
    // return;
    // }
    // Download[] dl2abort = new Download[rows.length];
    // for (int i = 0; i < rows.length; i++) {
    // dl2abort[i] = tableModel.getDownloadAtRow(rows[i]);
    // }
    // Abort it two steps, because .abort causes model to change
    // for (int i = 0; i < dl2abort.length; i++) {
    // dl2abort[i].abort();
    // }
    // }
    // }
    // /**
    // * clears all completed uploads
    // *
    // * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc
    // </a>
    // * @version $Revision: 1.4 $
    // */
    // private class ClearCompletedAction extends BaseAction {
    // public ClearCompletedAction() {
    // super("clearcompleteddownloads", UploadsPanel.this.getController());
    // }
    //
    // public void actionPerformed(ActionEvent e) {
    // tableModel.clearCompleted();
    // }
    // }
    // Helper classes *********************************************************
    /**
     * Helper class which opens a popmenu when requested (right-mouseclick)
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     * @version $Revision: 1.4 $
     */
    // private class PopupMenuOpener extends MouseAdapter {
    // private JPopupMenu popupMenu;
    // private PopupMenuOpener(JPopupMenu popupMenu) {
    // if (popupMenu == null) {
    // throw new NullPointerException("Popupmenu is null");
    // }
    // this.popupMenu = popupMenu;
    // }
    // public void mousePressed(MouseEvent evt) {
    // if (evt.isPopupTrigger()) {
    // showContextMenu(evt);
    // }
    // }
    // public void mouseReleased(MouseEvent evt) {
    // if (evt.isPopupTrigger()) {
    // showContextMenu(evt);
    // }
    // }
    //      
    // private void showContextMenu(MouseEvent evt) {
    // updateActions();
    // popupMenu.show(evt.getComponent(), evt.getX(), evt.getY());
    // }
    // }
}