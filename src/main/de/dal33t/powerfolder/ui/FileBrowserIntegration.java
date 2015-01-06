/*
 * Copyright 2004 - 2014 Christian Sprajc. All rights reserved.
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
 */
package de.dal33t.powerfolder.ui;

import com.liferay.nativity.control.NativityControl;
import com.liferay.nativity.control.NativityControlUtil;
import com.liferay.nativity.modules.contextmenu.ContextMenuControlUtil;
import com.liferay.nativity.modules.fileicon.FileIconControlUtil;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.disk.FolderRepository;
import de.dal33t.powerfolder.disk.Locking;
import de.dal33t.powerfolder.transfer.TransferManager;
import de.dal33t.powerfolder.ui.contextmenu.ContextMenuHandler;
import de.dal33t.powerfolder.ui.iconoverlay.IconOverlayHandler;
import de.dal33t.powerfolder.ui.iconoverlay.IconOverlayUpdateListener;
import de.dal33t.powerfolder.util.os.OSUtil;

/**
 * Enable Overlay Icons and context menu entries on the different platforms
 * using the library of Liferay Nativity.
 * 
 * @author <a href="mailto:krickl@powerfolder.com">Maximilian Krickl</a>
 */
public class FileBrowserIntegration extends PFComponent {

    private NativityControl nc;
    private IconOverlayHandler iconOverlayHandler;
    private IconOverlayUpdateListener updateListener;

    public FileBrowserIntegration(Controller controller) {
        super(controller);
        updateListener = new IconOverlayUpdateListener(controller);
    }

    /**
     * Start up the shell extensions according to the OS we are running on. Adds
     * different listeners and visitors to {@link Folder}s,
     * {@link FolderRepository}, {@link Locking} and the {@link TransferManager}
     * 
     * @return {@code True} if the shell extensions could be loaded/started,
     *         {@code false} otherwise.
     */
    public boolean start() {
        logFine("Starting file browser integration");
        nc = NativityControlUtil.getNativityControl();
        if (nc == null) {
            logFine("Could not start file browser integration");
            return false;
        }

        FolderRepository repo = getController().getFolderRepository();
        for (Folder folder : repo.getFolders()) {
            folder.addFolderListener(updateListener);
        }
        repo.addFolderRepositoryListener(updateListener);
        repo.getLocking().addListener(updateListener);
        getController().getTransferManager().addListener(updateListener);

        if (OSUtil.isWindowsSystem()) {
            logFine("Connect file browser integration to Windows");
            return fbWindows(nc);
        } else if (OSUtil.isMacOS()) {
            logFine("Connect file browser integration to OS X");
            return fbApple(nc);
        }

        return false;
    }

    /**
     * Prepare the local socket for communication with the OS X AppleScript
     * Finder integration.
     * 
     * @param nc
     * @return {@code True} if connection was set up correctly, {@code false}
     *         otherwise.
     */
    private boolean fbApple(NativityControl nc) {
        try {
            if (!nc.loaded()) {
                if (!nc.load()) {
                    logWarning("Could not load the finder integration.");
                    return false;
                }
            }

            if (!nc.connect()) {
                logWarning("Could not connect to finder integration.");

                nc.disconnect();
                return false;
            } else {
                iconOverlayHandler = new IconOverlayHandler(getController());
                FileIconControlUtil.getFileIconControl(nc, iconOverlayHandler)
                    .enableFileIcons();

                return true;
            }
        } catch (Exception re) {
            logWarning("Could not start finder integration. " + re);
            return false;
        }
    }

    /**
     * Prepare the local socket for communictaion with the windows dlls.
     * 
     * @param nc
     * @return {@code True} if connection was set up correctly, {@code false}
     *         otherwise.
     */
    private boolean fbWindows(NativityControl nc) {
        try {
            if (!nc.connect()) {
                logWarning("Could not connect to shell extensions!");

                nc.disconnect();
                return false;
            } else {
                if (PreferencesEntry.ENABLE_CONTEXT_MENU
                    .getValueBoolean(getController()))
                {
                    logFine("Initializing context menu");
                    ContextMenuControlUtil.getContextMenuControl(nc,
                        new ContextMenuHandler(getController()));
                }

                logFine("Initializing icon overlays");
                iconOverlayHandler = new IconOverlayHandler(getController());
                FileIconControlUtil.getFileIconControl(nc, iconOverlayHandler)
                    .enableFileIcons();
            }

            return true;
        } catch (RuntimeException re) {
            logWarning("Could not start shell extensions. " + re);
            return false;
        }
    }

    /**
     * Lifecycle management. Removes the listeners and visitors.<br />
     * Should be called, when shutting down the client.
     */
    public void shutdown() {
        FileIconControlUtil.getFileIconControl(nc, iconOverlayHandler)
            .disableFileIcons();

        if (OSUtil.isMacOS()) {
            try {
                nc.unload();
            } catch (Exception e) {
                logWarning("Could not unload FileBrowswerIntegration: " + e);
            }
        }
        nc.disconnect();

        getController().getFolderRepository().getLocking()
            .removeListener(updateListener);
        getController().getFolderRepository().removeFolderRepositoryListener(
            updateListener);
        getController().getTransferManager().removeListener(updateListener);
        FolderRepository repo = getController().getFolderRepository();
        for (Folder folder : repo.getFolders()) {
            folder.removeFolderListener(updateListener);
        }
    }
}
