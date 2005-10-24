/*
 * ====================================================================
 * Copyright (c) 2004 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://tmate.org/svn/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */

package org.tmatesoft.svn.cli.command;

import org.tmatesoft.svn.cli.SVNArgument;
import org.tmatesoft.svn.cli.SVNCommand;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNCommitClient;

import java.io.File;
import java.io.PrintStream;

/**
 * @author TMate Software Ltd.
 */
public class CommitCommand extends SVNCommand {

    public void run(final PrintStream out, PrintStream err) throws SVNException {
        checkEditorCommand();
        final boolean recursive = !getCommandLine().hasArgument(SVNArgument.NON_RECURSIVE);
        boolean keepLocks = getCommandLine().hasArgument(SVNArgument.NO_UNLOCK);
        final String message = getCommitMessage();

        File[] localPaths = new File[getCommandLine().getPathCount()];
        for (int i = 0; i < getCommandLine().getPathCount(); i++) {
            matchTabsInPath(getCommandLine().getPathAt(i), err);
            localPaths[i] = new File(getCommandLine().getPathAt(i));
        }
        getClientManager().setEventHandler(new SVNCommandEventProcessor(out, err, false));
        SVNCommitClient client = getClientManager().getCommitClient();
        SVNCommitInfo result = client.doCommit(localPaths, keepLocks, message, false, recursive);
        if (result != SVNCommitInfo.NULL) {
            out.println();
            out.println("Committed revision " + result.getNewRevision() + ".");
        }
    }

    private void checkEditorCommand() throws SVNException {
        final String editorCommand = (String) getCommandLine().getArgumentValue(SVNArgument.EDITOR_CMD);
        if (editorCommand == null) {
            return;
        }

        throw new SVNException("Commit failed. Can't handle external editor " + editorCommand);
    }
}
