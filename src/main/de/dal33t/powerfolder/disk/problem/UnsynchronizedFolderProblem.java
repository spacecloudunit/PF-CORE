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
 * $Id: UnsynchronizedFolderProblem.java 7985 2009-05-18 07:17:34Z harry $
 */
package de.dal33t.powerfolder.disk.problem;

import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.ui.WikiLinks;
import de.dal33t.powerfolder.util.Translation;

import java.util.Date;

/**
 * Problem where a folder has not been synchronized in n days.
 */
public class UnsynchronizedFolderProblem implements Problem {

    private static final String WIKI_LINK_KEY = WikiLinks.PROBLEM_UNSYNCED;

    private final String description;
    private final Date date;

    public UnsynchronizedFolderProblem(FolderInfo folderInfo, int days) {
        description = Translation.getTranslation("folder_problem.unsynchronized",
                folderInfo.name, days);
        date = new Date();
    }

    public String getDescription() {
        return description;
    }

    public String getWikiLinkKey() {
        return WIKI_LINK_KEY;
    }

    public Date getDate() {
        return date;
    }
}
