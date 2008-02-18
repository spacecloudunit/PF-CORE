package de.dal33t.powerfolder.security;

import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.util.Reject;

/**
 * The permission to read files in the folder. Write
 * 
 * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public class FolderReadPermission implements Permission {
    private static final long serialVersionUID = 100L;

    private FolderInfo folder;

    public FolderReadPermission(FolderInfo foInfo) {
        Reject.ifNull(foInfo, "Folderinfo is null");
        folder = foInfo;
    }

    public FolderInfo getFolder() {
        return folder;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((folder == null) ? 0 : folder.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final FolderReadPermission other = (FolderReadPermission) obj;
        if (folder == null) {
            if (other.folder != null)
                return false;
        } else if (!folder.equals(other.folder))
            return false;
        return true;
    }
}
