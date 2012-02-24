package org.tmatesoft.svn.core.internal.wc2.ng;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.regnis.q.sequence.line.diff.QDiffGenerator;
import de.regnis.q.sequence.line.diff.QDiffGeneratorFactory;
import de.regnis.q.sequence.line.diff.QDiffManager;
import de.regnis.q.sequence.line.diff.QDiffUniGenerator;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNMergeRangeList;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.internal.util.SVNHashMap;
import org.tmatesoft.svn.core.internal.util.SVNMergeInfoUtil;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.wc.ISVNReturnValueCallback;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.wc.SVNDiffOptions;
import org.tmatesoft.svn.util.SVNLogType;

public class SvnDiffGenerator implements ISvnDiffGenerator {

    protected static final String WC_REVISION_LABEL = "(working copy)";
    protected static final String PROPERTIES_SEPARATOR = "___________________________________________________________________";
    protected static final String HEADER_SEPARATOR = "===================================================================";

    private String path1;
    private String path2;
    private String encoding;
    private byte[] eol;
    private boolean useGitFormat;
    private boolean forcedBinaryDiff;

    private boolean diffDeleted;
    private List<String> rawDiffOptions;
    private SVNDiffOptions svnDiffOptions;
    private boolean forceEmpty;

    public SvnDiffGenerator() {
        this.path1 = "";
        this.path2 = "";
    }

    public void init(String path1, String path2) {
        this.path1 = path1;
        this.path2 = path2;
    }

    public void setForceEmpty(boolean forceEmpty) {
        this.forceEmpty = forceEmpty;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getEncoding() {
        return encoding;
    }

    public String getGlobalEncoding() {
        return null; //TODO
    }

    public void setEOL(byte[] eol) {
        this.eol = eol;
    }

    public byte[] getEOL() {
        return eol;
    }

    public boolean isForcedBinaryDiff() {
        return forcedBinaryDiff;
    }

    public void setForcedBinaryDiff(boolean forcedBinaryDiff) {
        this.forcedBinaryDiff = forcedBinaryDiff;
    }

    public void displayDeletedDirectory(String displayPath, String revision1, String revision2, OutputStream outputStream) throws SVNException {
    }

    public void displayAddedDirectory(String displayPath, String revision1, String revision2, OutputStream outputStream) throws SVNException {
    }

    public void displayPropsChanged(String displayPath, String revision1, String revision2, boolean dirWasAdded, SVNProperties originalProps, SVNProperties propChanges, boolean showDiffHeader, OutputStream outputStream) throws SVNException {
        ensureEncodingAndEOLSet();

        if (useGitFormat) {
            //TODO adjust paths to point to repository root
        }

        if (displayPath == null || displayPath.length() == 0) {
            displayPath = ".";
        }

        if (showDiffHeader) {
            String commonAncestor = SVNPathUtil.getCommonPathAncestor(path1, path2);
            if (commonAncestor == null) {
                commonAncestor = "";
            }

            String adjustedPathWithLabel1 = getAdjustedPathWithLabel(displayPath, path1, revision1, commonAncestor);
            String adjustedPathWithLabel2 = getAdjustedPathWithLabel(displayPath, path2, revision2, commonAncestor);

            if (displayHeader(outputStream, displayPath, false)) {
                return;
            }

            if (useGitFormat) {
                displayGitDiffHeader(adjustedPathWithLabel1, adjustedPathWithLabel2, SvnDiffCallback.OperationKind.Modified, path1, path2, revision1, revision2, null);
            }

            displayHeaderFields(outputStream, adjustedPathWithLabel1, adjustedPathWithLabel2);
        }

        displayPropertyChangesOn(useGitFormat ? path1 : displayPath, outputStream);

        displayPropDiffValues(outputStream, propChanges, originalProps);
    }

    public void displayContentChanged(String displayPath, File leftFile, File rightFile, String revision1, String revision2, String mimeType1, String mimeType2, SvnDiffCallback.OperationKind operation, File copyFromPath, OutputStream outputStream) throws SVNException {
        ensureEncodingAndEOLSet();

        String commonAncestor = SVNPathUtil.getCommonPathAncestor(path1, path2);
        if (commonAncestor == null) {
            commonAncestor = "";
        }

        String adjustedPathWithLabel1 = getAdjustedPathWithLabel(displayPath, path1, revision1, commonAncestor);
        String adjustedPathWithLabel2 = getAdjustedPathWithLabel(displayPath, path2, revision2, commonAncestor);

        String label1 = adjustedPathWithLabel1;
        String label2 = adjustedPathWithLabel2;

        boolean leftIsBinary = false;
        boolean rightIsBinary = false;

        if (mimeType1 != null) {
            leftIsBinary = SVNProperty.isBinaryMimeType(mimeType1);
        }
        if (mimeType2 != null) {
            rightIsBinary = SVNProperty.isBinaryMimeType(mimeType2);
        }

        if (!forcedBinaryDiff && (leftIsBinary || rightIsBinary)) {
            if (displayHeader(outputStream, displayPath, rightFile == null)) {
                return;
            }

            displayBinary(mimeType1, mimeType2, outputStream, leftIsBinary, rightIsBinary);

            return;
        }

        final String diffCommand = getExternalDiffCommand();
        if (diffCommand != null) {
            if (displayHeader(outputStream, displayPath, rightFile == null)) {
                return;
            }

            runExternalDiffCommand(outputStream, diffCommand, leftFile, rightFile, label1, label2);
        } else {
            internalDiff(outputStream, displayPath, leftFile, rightFile, label1, label2);
        }
    }

    private void displayBinary(String mimeType1, String mimeType2, OutputStream outputStream, boolean leftIsBinary, boolean rightIsBinary) throws SVNException {
        displayCannotDisplayFileMarkedBinary(outputStream);

        if (leftIsBinary && !rightIsBinary) {
            displayMimeType(outputStream, mimeType2);
        } else if (!leftIsBinary && rightIsBinary) {
            displayMimeType(outputStream, mimeType2);
        } else if (leftIsBinary && rightIsBinary) {
            if (mimeType1.equals(mimeType2)) {
                displayMimeType(outputStream, mimeType1);
            } else {
                displayMimeTypes(outputStream, mimeType1, mimeType2);
            }
        }
    }

    private void internalDiff(OutputStream outputStream, String displayPath, File file1, File file2, String label1, String label2) throws SVNException {
        String header = getHeaderString(displayPath, label1, label2);
        String headerFields = getHeaderFieldsString(displayPath, label1, label2);

        RandomAccessFile is1 = null;
        RandomAccessFile is2 = null;
        try {
            is1 = file1 == null ? null : SVNFileUtil.openRAFileForReading(file1);
            is2 = file2 == null ? null : SVNFileUtil.openRAFileForReading(file2);

            QDiffUniGenerator.setup();
            Map properties = new SVNHashMap();

            properties.put(QDiffGeneratorFactory.IGNORE_EOL_PROPERTY, Boolean.valueOf(getSvnDiffOptions().isIgnoreEOLStyle()));
            if (getSvnDiffOptions().isIgnoreAllWhitespace()) {
                properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_ALL_SPACE);
            } else if (getSvnDiffOptions().isIgnoreAmountOfWhitespace()) {
                properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_SPACE_CHANGE);
            }

            final String diffHeader;
            if (forceEmpty) {
                displayString(outputStream, header);
                diffHeader = headerFields;
            } else {
                diffHeader = header + headerFields;
            }
            QDiffGenerator generator = new QDiffUniGenerator(properties, diffHeader);
            Writer writer = new OutputStreamWriter(outputStream, getEncoding());
            QDiffManager.generateTextDiff(is1, is2, getEncoding(), writer, generator);
            writer.flush();
        } catch (IOException e) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, e.getMessage());
            SVNErrorManager.error(err, e, SVNLogType.DEFAULT);
        } finally {
            SVNFileUtil.closeFile(is1);
            SVNFileUtil.closeFile(is2);
        }
    }

    private String getHeaderFieldsString(String displayPath, String label1, String label2) throws SVNException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            if (useGitFormat) {
                //
            }
            displayHeaderFields(byteArrayOutputStream, label1, label2);
        } catch (SVNException e) {
            SVNFileUtil.closeFile(byteArrayOutputStream);

            try {
                byteArrayOutputStream.writeTo(byteArrayOutputStream);
            } catch (IOException e1) {
            }

            throw e;
        }

        try {
            byteArrayOutputStream.close();
            return byteArrayOutputStream.toString(getEncoding());
        } catch (IOException e) {
            return "";
        }
    }

    private String getHeaderString(String displayPath, String label1, String label2) throws SVNException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            if (displayHeader(byteArrayOutputStream, displayPath, false)) {
                SVNFileUtil.closeFile(byteArrayOutputStream);
                return null;
            }
            if (useGitFormat) {
                //
            }
//            displayHeaderFields(byteArrayOutputStream, label1, label2);
        } catch (SVNException e) {
            SVNFileUtil.closeFile(byteArrayOutputStream);

            try {
                byteArrayOutputStream.writeTo(byteArrayOutputStream);
            } catch (IOException e1) {
            }

            throw e;
        }

        try {
            byteArrayOutputStream.close();
            return byteArrayOutputStream.toString(getEncoding());
        } catch (IOException e) {
            return "";
        }
    }

    private void runExternalDiffCommand(OutputStream outputStream, final String diffCommand, File file1, File file2, String label1, String label2) throws SVNException {
        final List<String> args = new ArrayList<String>();
        args.add(diffCommand);
        if (rawDiffOptions != null) {
            args.addAll(rawDiffOptions);
        } else {
            Collection svnDiffOptionsCollection = getSvnDiffOptions().toOptionsCollection();
            args.addAll(svnDiffOptionsCollection);
            args.add("-u");
        }

        if (label1 != null) {
            args.add("-L");
            args.add(label1);
        }

        if (label2 != null) {
            args.add("-L");
            args.add(label2);
        }

        boolean tmpFile1 = false;
        boolean tmpFile2 = false;
        if (file1 == null) {
            file1 = SVNFileUtil.createTempFile("svn.", ".tmp");
            tmpFile1 = true;
        }
        if (file2 == null) {
            file2 = SVNFileUtil.createTempFile("svn.", ".tmp");
            tmpFile2 = true;
        }

        String currentDir = new File("").getAbsolutePath().replace(File.separatorChar, '/');
        String file1Path = file1.getAbsolutePath().replace(File.separatorChar, '/');
        String file2Path = file2.getAbsolutePath().replace(File.separatorChar, '/');

        if (file1Path.startsWith(currentDir)) {
            file1Path = file1Path.substring(currentDir.length());
            file1Path = file1Path.startsWith("/") ? file1Path.substring(1) : file1Path;
        }

        if (file2Path.startsWith(currentDir)) {
            file2Path = file2Path.substring(currentDir.length());
            file2Path = file2Path.startsWith("/") ? file2Path.substring(1) : file2Path;
        }

        args.add(file1Path);
        args.add(file2Path);
            try {
                final Writer writer = new OutputStreamWriter(outputStream, getEncoding());

                SVNFileUtil.execCommand(args.toArray(new String[args.size()]), true,
                        new ISVNReturnValueCallback() {

                    public void handleReturnValue(int returnValue) throws SVNException {
                        if (returnValue != 0 && returnValue != 1) {
                            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.EXTERNAL_PROGRAM,
                                    "''{0}'' returned {1}", new Object[] { diffCommand, String.valueOf(returnValue) });
                            SVNErrorManager.error(err, SVNLogType.DEFAULT);
                        }
                    }

                    public void handleChar(char ch) throws SVNException {
                        try {
                            writer.write(ch);
                        } catch (IOException ioe) {
                            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getMessage());
                            SVNErrorManager.error(err, ioe, SVNLogType.DEFAULT);
                        }
                    }

                    public boolean isHandleProgramOutput() {
                        return true;
                    }
                });

                writer.flush();
            } catch (IOException ioe) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getMessage());
                SVNErrorManager.error(err, ioe, SVNLogType.DEFAULT);
            } finally {
                try {
                    if (tmpFile1) {
                        SVNFileUtil.deleteFile(file1);
                    }
                    if (tmpFile2) {
                        SVNFileUtil.deleteFile(file2);
                    }
                } catch (SVNException e) {
                    // skip
                }
            }
    }

    private String getExternalDiffCommand() {
        return null;
    }

    private void displayMimeType(OutputStream outputStream, String mimeType) throws SVNException {
        try {
            displayString(outputStream, SVNProperty.MIME_TYPE);
            displayString(outputStream, " = ");
            displayString(outputStream, mimeType);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayMimeTypes(OutputStream outputStream, String mimeType1, String mimeType2) throws SVNException {
        try {
            displayString(outputStream, SVNProperty.MIME_TYPE);
            displayString(outputStream, " = (");
            displayString(outputStream, mimeType1);
            displayString(outputStream, ", ");
            displayString(outputStream, mimeType2);
            displayString(outputStream, ")");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayCannotDisplayFileMarkedBinary(OutputStream outputStream) throws SVNException {
        try {
            displayString(outputStream, "Cannot display: file marked as a binary type.");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void ensureEncodingAndEOLSet() {
        if (getEOL() == null) {
            setEOL(SVNProperty.EOL_LF_BYTES);
        }
        if (getEncoding() == null) {
            setEncoding("UTF-8");
        }
    }

    private void displayPropDiffValues(OutputStream outputStream, SVNProperties diff, SVNProperties baseProps) throws SVNException {
        for (Iterator changedPropNames = diff.nameSet().iterator(); changedPropNames.hasNext();) {
            String name = (String) changedPropNames.next();
            SVNPropertyValue originalValue = baseProps != null ? baseProps.getSVNPropertyValue(name) : null;
            SVNPropertyValue newValue = diff.getSVNPropertyValue(name);
            String headerFormat = null;

            if (originalValue == null) {
                headerFormat = "Added: ";
            } else if (newValue == null) {
                headerFormat = "Deleted: ";
            } else {
                headerFormat = "Modified: ";
            }

            try {
                displayString(outputStream, (headerFormat + name));
                displayEOL(outputStream);
                if (SVNProperty.MERGE_INFO.equals(name)) {
                    displayMergeInfoDiff(outputStream, originalValue == null ? null : originalValue.getString(), newValue == null ? null : newValue.getString());
                    continue;
                }
                if (originalValue != null) {
                    displayString(outputStream, "   - ");
                    outputStream.write(getPropertyAsBytes(originalValue, getEncoding()));
                    displayEOL(outputStream);
                }
                if (newValue != null) {
                    displayString(outputStream, "   + ");
                    outputStream.write(getPropertyAsBytes(newValue, getEncoding()));
                    displayEOL(outputStream);
                }
                displayEOL(outputStream);
            } catch (IOException e) {
                wrapException(e);
            }
        }

    }

    private void displayGitDiffHeader(String label1, String label2, SvnDiffCallback.OperationKind operationKind, String path1, String path2, String revision1, String revision2, String copyFromPath) {
        throw new UnsupportedOperationException();//TODO
    }

    private String getAdjustedPathWithLabel(String displayPath, String path, String revision, String commonAncestor) {
        String adjustedPath = getAdjustedPath(displayPath, path, commonAncestor);
        return getLabel(adjustedPath, revision);
    }

    private String getAdjustedPath(String displayPath, String path1, String commonAncestor) {
        String adjustedPath = SVNPathUtil.getRelativePath(commonAncestor, path1);

        if (adjustedPath == null || adjustedPath.length() == 0) {
            adjustedPath = displayPath;
        } else if (adjustedPath.charAt(0) == '/') {
            adjustedPath = displayPath + "\t(..." + adjustedPath + ")";
        } else {
            adjustedPath = displayPath + "\t(.../" + adjustedPath + ")";
        }
        return adjustedPath;
        //TODO: respect relativeToDir
    }

    protected String getLabel(String path, String revToken) {
        revToken = revToken == null ? WC_REVISION_LABEL : revToken;
        return path + "\t" + revToken;
    }

    protected boolean displayHeader(OutputStream os, String path, boolean deleted) throws SVNException {
        try {
            if (deleted && !isDiffDeleted()) {
                displayString(os, "Index: ");
                displayString(os, path);
                displayString(os, " (deleted)");
                displayEOL(os);
                displayString(os, HEADER_SEPARATOR);
                displayEOL(os);
                return true;
            }
            displayString(os, "Index: ");
            displayString(os, path);
            displayEOL(os);
            displayString(os, HEADER_SEPARATOR);
            displayEOL(os);
            return false;
        } catch (IOException e) {
            wrapException(e);
        }
        return false;
    }

    protected void displayHeaderFields(OutputStream os, String label1, String label2) throws SVNException {
        try {
            displayString(os, "--- ");
            displayString(os, label1);
            displayEOL(os);
            displayString(os, "+++ ");
            displayString(os, label2);
            displayEOL(os);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayPropertyChangesOn(String path, OutputStream outputStream) throws SVNException {
        try {
            displayEOL(outputStream);
            displayString(outputStream, ("Property changes on: " + (useLocalFileSeparatorChar() ? path.replace('/', File.separatorChar) : path)));
            displayEOL(outputStream);
            displayString(outputStream, PROPERTIES_SEPARATOR);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private byte[] getPropertyAsBytes(SVNPropertyValue value, String encoding){
        if (value == null){
            return null;
        }
        if (value.isString()){
            try {
                return value.getString().getBytes(encoding);
            } catch (UnsupportedEncodingException e) {
                return value.getString().getBytes();
            }
        }
        return value.getBytes();
    }

    private void displayMergeInfoDiff(OutputStream outputStream, String oldValue, String newValue) throws SVNException, IOException {
        Map oldMergeInfo = null;
        Map newMergeInfo = null;
        if (oldValue != null) {
            oldMergeInfo = SVNMergeInfoUtil.parseMergeInfo(new StringBuffer(oldValue), null);
        }
        if (newValue != null) {
            newMergeInfo = SVNMergeInfoUtil.parseMergeInfo(new StringBuffer(newValue), null);
        }

        Map deleted = new TreeMap();
        Map added = new TreeMap();
        SVNMergeInfoUtil.diffMergeInfo(deleted, added, oldMergeInfo, newMergeInfo, true);

        for (Iterator paths = deleted.keySet().iterator(); paths.hasNext();) {
            String path = (String) paths.next();
            SVNMergeRangeList rangeList = (SVNMergeRangeList) deleted.get(path);
            displayString(outputStream, ("   Reverse-merged " + path + ":r"));
            displayString(outputStream, rangeList.toString());
            displayEOL(outputStream);
        }

        for (Iterator paths = added.keySet().iterator(); paths.hasNext();) {
            String path = (String) paths.next();
            SVNMergeRangeList rangeList = (SVNMergeRangeList) added.get(path);
            displayString(outputStream, ("   Merged " + path + ":r"));
            displayString(outputStream, rangeList.toString());
            displayEOL(outputStream);
        }
    }

    private boolean useLocalFileSeparatorChar() {
        return true;
    }

    public boolean isDiffDeleted() {
        return diffDeleted;
    }

    private void wrapException(IOException e) throws SVNException {
        SVNErrorMessage errorMessage = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, e);
        SVNErrorManager.error(errorMessage, e, SVNLogType.WC);
    }

    private void displayString(OutputStream outputStream, String s) throws IOException {
        outputStream.write(s.getBytes(getEncoding()));
    }

    private void displayEOL(OutputStream os) throws IOException {
        os.write(getEOL());
    }

    public SVNDiffOptions getSvnDiffOptions() {
        if (svnDiffOptions == null) {
            svnDiffOptions = new SVNDiffOptions();
        }
        return svnDiffOptions;
    }
}
