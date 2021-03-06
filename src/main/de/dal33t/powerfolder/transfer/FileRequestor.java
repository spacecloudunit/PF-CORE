
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
 * $Id: FileRequestor.java 20866 2013-02-18 10:35:48Z sprajc $
 */
package de.dal33t.powerfolder.transfer;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Constants;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.light.DirectoryInfo;
import de.dal33t.powerfolder.light.FileHistory;
import de.dal33t.powerfolder.light.FileHistory.Conflict;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.message.FileHistoryReply;
import de.dal33t.powerfolder.util.ProblemUtil;
import de.dal33t.powerfolder.util.Profiling;
import de.dal33t.powerfolder.util.ProfilingEntry;
import de.dal33t.powerfolder.util.Reject;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The filerequestor handles all stuff about requesting new downloads
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.18 $
 */
public class FileRequestor extends PFComponent {
    // 60 seconds
    private static final long PERIODIC_REQUEST_MS = 1000L * 60;
    // 30 minutes
    private static final long WORKER_TIMEOUT = PERIODIC_REQUEST_MS * 30;
    private final Queue<Worker> workerPool;
    private final Queue<Folder> folderQueue;
    private final Queue<FileInfo> pendingRequests;

    public FileRequestor(Controller controller) {
        super(controller);
        folderQueue = new ConcurrentLinkedQueue<Folder>();
        pendingRequests = new ConcurrentLinkedQueue<FileInfo>();
        workerPool = new ConcurrentLinkedQueue<Worker>();
    }

    /**
     * Starts the file requestor
     */
    public void start() {
        logFine("Started");
        getController()
            .scheduleAndRepeat(new PeriodicalTriggerTask(), PERIODIC_REQUEST_MS);
    }

    /**
     * Triggers the worker to request new files on the given folder.
     * 
     * @param foInfo
     *            the folder to request files on
     */
    public void triggerFileRequesting(FolderInfo foInfo) {
        Reject.ifNull(foInfo, "Folder is null");

        Folder folder = foInfo.getFolder(getController());
        if (folder == null) {
            logWarning("Folder not joined, not requesting files: " + foInfo);
            return;
        }
        synchronized (folderQueue) {
            if (folderQueue.contains(folder)) {
                return;
            }
            folderQueue.offer(folder);
            addWorker();
        }
    }

    /**
     * Triggers to request missing files on all folders.
     * 
     * @see #triggerFileRequesting(FolderInfo) for single folder file requesting
     *      (=lower CPU usage)
     */
    public void triggerFileRequesting() {
        ProfilingEntry pe = Profiling.start();
        Collection<Folder> folders = getController().getFolderRepository()
            .getFolders(true);
        synchronized (folderQueue) {
            for (Folder folder : folders) {
                if (folderQueue.contains(folder)) {
                    continue;
                }
                folderQueue.offer(folder);
                addWorker();
            }
        }
        Profiling.end(pe, 100);
    }

    /**
     * Stops file requsting
     */
    public void shutdown() {
        for (Worker worker : workerPool) {
            worker.stopped = true;
        }
        synchronized (folderQueue) {
            folderQueue.notifyAll();
        }
        logFine("Stopped");
    }

    /**
     * Checks all new received filelists from member and downloads unknown/new
     * files, force the settings.
     * <p>
     * FIXME: Does requestFromFriends work?
     * 
     * @param folder
     * @param autoDownload
     */
    public void requestMissingFiles(Folder folder, boolean autoDownload) {
        // Dont request files until has own database
        if (!folder.hasOwnDatabase()) {
            return;
        }
        Collection<FileInfo> incomingFiles = folder.getIncomingFiles(false,
            Constants.MAX_DLS_FROM_LAN_MEMBER * 2);
        retrieveNewestVersions(folder, incomingFiles, autoDownload);
    }

    /**
     * Requests missing files for autodownload. May not request any files if
     * folder is not in auto download sync profile. Checks the syncprofile for
     * each file. Sysncprofile may change in the meantime.
     * 
     * @param folder
     *            the folder to request missing files on.
     */
    private void requestMissingFilesForAutodownload(Folder folder) {
        if (getController().isPaused()) {
            if (isFiner()) {
                logFiner("Paused: Skipping request of new files.");
            }
            return;
        }
        if (!folder.getSyncProfile().isAutodownload()) {
            if (isFiner()) {
                logFiner("Skipping " + folder + ". not on auto donwload");
            }
            return;
        }
        if (isFiner()) {
            logFiner("Requesting files (autodownload), has own DB? "
                + folder.hasOwnDatabase());
        }

        if (!folder.isStarted()) {
            if (isFiner()) {
                logFiner("Not requesting files. Folder not started yet "
                    + folder);
            }
            return;
        }
        if (folder.isDeviceDisconnected()) {
            if (isFine()) {
                logFine("Not requesting files. Device disconnected of "
                    + folder);
            }
            return;
        }
        if (!folder.hasOwnDatabase()) {
            if (folder.isScanning() || folder.isDeviceDisconnected()) {
                if (isFine()) {
                    logFine("Not requesting files. No own database for "
                        + folder);
                }
            } else {
                if (isWarning()) {
                    logWarning("Not requesting files. No own database for "
                        + folder);
                }
            }
            return;
        }
        if (folder.getConnectedMembersCount() == 0) {
            if (isFiner()) {
                logFiner("Not requesting files. No member connected on "
                    + folder);
            }
            return;
        }
        if (!folder.hasUploadCapacity()) {
            if (isFiner()) {
                logFiner("Not requesting files. Members of folder don't have upload capacity "
                    + folder);
            }
            return;
        }
        Collection<FileInfo> incomingFiles = folder.getIncomingFiles(false,
            Constants.MAX_DLS_FROM_LAN_MEMBER * 2);
        if (incomingFiles.isEmpty()) {
            if (isFiner()) {
                logFiner("Not requesting files. No incoming files " + folder);
            }
            return;
        }

        retrieveNewestVersions(folder, incomingFiles, true);
    }

    /**
     * Utility method that will retrieve (download or make directory) the newest
     * version of all given files provided that the given filter accepts a file.
     * 
     * @param folder
     * @param fInfos
     * @param autoDownload
     */
    private void retrieveNewestVersions(Folder folder,
        Collection<FileInfo> fInfos, boolean autoDownload)
    {
        TransferManager tm = getController().getTransferManager();
        List<FileInfo> filesToDownload = new ArrayList<FileInfo>(fInfos.size());
        int i = 0;
        for (FileInfo fInfo : fInfos) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (fInfo.isDeleted()) {
                // Dont retrieve deleted. done in a different place:
                // Folder.syncRemoteDeletions.
                continue;
            }
            if (fInfo.isFile()) {
                if (tm.isDownloadingActive(fInfo)
                    || tm.isDownloadingPending(fInfo))
                {
                    // Already downloading/file
                    continue;
                }
            }

            if (fInfo.isFile()) {
                filesToDownload.add(fInfo);
            } else if (fInfo.isDiretory()) {
                createDirectory((DirectoryInfo) fInfo);
            }
        }
        if (filesToDownload.isEmpty()) {
            // Quit here.
            return;
        }
        Collections.sort(filesToDownload, folder.getTransferPriorities().getComparator());
        for (FileInfo fInfo : filesToDownload) {
            try {
                // Safeguard:
                FileInfo newestVersion = fInfo.getNewestVersion(getController()
                    .getFolderRepository());
                if (newestVersion == null) {
                    logFine("Unable to download. Newest version not found: "
                        + fInfo.toDetailString());
                    continue;
                }
                prepareDownload(newestVersion, autoDownload);
            } catch (RuntimeException e) {
                logWarning("Unable to download: " + fInfo.toDetailString()
                    + ": " + e);
            }
        }
    }

    private void createDirectory(DirectoryInfo dirInfo) {
        Path dirFile = dirInfo.getDiskFile(getController()
            .getFolderRepository());
        Folder folder = dirInfo
            .getFolder(getController().getFolderRepository());
        if (folder == null || dirFile == null) {
            logWarning("Unable to created directory. not longer on folder: "
                + dirInfo.toDetailString());
            return;
        }
        folder.scanDirectory(dirInfo, dirFile);
        if (isFine()) {
            logFine("Synced directory: " + dirInfo.toDetailString());
        }
    }

    private void prepareDownload(FileInfo newestVersion, boolean autoDownload) {
        TransferManager tm = getController().getTransferManager();
        tm.downloadNewestVersion(newestVersion, autoDownload);
    }

    /**
     * Called if a FileHistory was received.
     * 
     * @param fhReply
     */
    public void receivedFileHistory(final FileHistoryReply fhReply) {
        Reject.notNull(fhReply, "fhReply");
        if (!pendingRequests.remove(fhReply.getRequestFileInfo())) {
            logWarning("Received FileHistory for unrequested FileInfo "
                + fhReply.getRequestFileInfo());
            return;
        }
        getController().getIOProvider().startIO(new Runnable() {
            public void run() {
                checkForConflict(fhReply);
            }
        });
    }

    private void checkForConflict(FileHistoryReply fhRepl) {
        FileInfo fi = fhRepl.getRequestFileInfo();
        FileHistory fh = fhRepl.getFileHistory();
        if (fh == null) {
            logFine("Remote client claims not to have a history for " + fi
                + ", not downloading!");
            logFine("That was a lie, since there are no FileHistories I'll download it anyways!");
            // FIXME But it should not download the file and
            // abort instead if FileHistories come available!
            getController().getTransferManager()
                .downloadNewestVersion(fi, true);
        } else {
            FileHistory localHistory = fi.getFolder(
                getController().getFolderRepository()).getDAO().getFileHistory(
                fi);
            if (localHistory == null) {
                logSevere("Local FileHistory missing for " + fi
                    + ", not downloading!");
            } else {
                Conflict conflict = localHistory.getConflictWith(fh);
                if (conflict != null) {
                    if (ProblemUtil.resolveConflict(conflict)) {
                        // The code currently only supports
                        // autoDownloads!
                        getController().getTransferManager()
                            .downloadNewestVersion(fi, true);
                    }
                } else {
                    // The code currently only supports
                    // autoDownloads!
                    getController().getTransferManager().downloadNewestVersion(
                        fi, true);
                }
            }
        }
    }

    private void addWorker() {
        synchronized (workerPool) {
            // Now do the actual resizing.
            int nWorkers = workerPool.size();
            // Calculate required workers. check min / max bounds.
            int maxWorkers = ConfigurationEntry.FOLDER_FILE_REQUESTOR_MAX_WORKERS.getValueInt(getController());
            int foldersPerWorker = 2* ConfigurationEntry.FOLDER_FOLDERS_PER_FILE_REQUESTOR.getValueInt(getController());
            int reqWorkers = Math.max(1, Math.min(maxWorkers, folderQueue.size() / foldersPerWorker));
            int diff = reqWorkers - nWorkers;

            if (isFiner() && diff != 0) {
                logFiner("nWorkers: " + nWorkers + ", required: " + reqWorkers + ". Diff: " + diff);
            }
            if (reqWorkers == maxWorkers) {
                logWarning("Maximum number of workers reached: " + maxWorkers + ". Try to increase this value by configuration");
            }
            for (int i = 0; i < diff; i++) {
                Worker worker = new Worker();
                workerPool.add(worker);
                getController().schedule(worker, 100L * i);
            }
        }
    }

    /**
     * Requests periodically new files from the folders
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     * @version $Revision: 1.18 $
     */
    private class Worker implements Runnable {
        private Date lastActivity = new Date();
        private volatile boolean stopped;

        /**
         * @return if the worker is busy/unavailable since 30 minutes.
         */
        private boolean isTimeout() {
            long msSinceLastActivity = System.currentTimeMillis() - lastActivity.getTime();
            return msSinceLastActivity > WORKER_TIMEOUT;
        }

        public void run() {
            try {
                if (folderQueue.isEmpty()) {
                    return;
                }
                if (stopped) {
                    return;
                }
                int nFolders = 0;
                if (isFiner()) {
                    logFiner("Started requesting files");
                }
                long start = System.currentTimeMillis();
                for (Folder folder : folderQueue) {
                    if (stopped) {
                        return;
                    }
                    /*
                    if (folderQueue.size() < 5) {
                        logWarning("Still in queue: " + folderQueue);
                    } else {
                        logWarning("Still in queue: "
                                + folderQueue.size());
                    }*/

                    try {
                        folderQueue.remove(folder);
                        nFolders++;
                        requestMissingFilesForAutodownload(folder);

                        // Give CPU a bit time.
                        if (nFolders % 5 == 0 && !stopped) {
                            lastActivity = new Date();
                            if (!getMySelf().isServer()) {
                                Thread.sleep(1);
                            }
                        }
                    } catch (RuntimeException e) {
                        logSevere("RuntimeException: " + e.toString(), e);
                    }
                }
                if (isFine()) {
                    long took = System.currentTimeMillis() - start;
                    logFine("Requesting files for " + nFolders + " folder(s) took " + took + "ms.");
                    if (took > PERIODIC_REQUEST_MS) {
                        logWarning("Requesting files for " + nFolders + " folder(s) took " + took + "ms.");
                    }
                }
            } catch (InterruptedException e) {
                logFine("Stopped");
                logFiner(e);
            } finally {
                stopped = true;
                workerPool.remove(this);
                if (!folderQueue.isEmpty()) {
                    addWorker();
                }
            }
        }
    }

    /**
     * Periodically triggers the file requesting on all folders.
     */
    private final class PeriodicalTriggerTask extends TimerTask {
        @Override
        public void run() {
            triggerFileRequesting();

            for (Worker worker : workerPool) {
                if (worker.isTimeout()) {
                    logWarning("Worker timed out detected. Restarting... " + worker);
                    worker.stopped = true;
                }
            }
        }
    }
}
