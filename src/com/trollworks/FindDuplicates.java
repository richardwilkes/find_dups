/*
 * Copyright (c) 2006-2014 by Richard A. Wilkes. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * version 2.0. If a copy of the MPL was not distributed with this file, You
 * can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as defined
 * by the Mozilla Public License, version 2.0.
 */

package com.trollworks;

import com.trollworks.toolkit.annotation.Localize;
import com.trollworks.toolkit.io.FileScanner;
import com.trollworks.toolkit.utility.Localization;
import com.trollworks.toolkit.utility.PathUtils;
import com.trollworks.toolkit.utility.Text;
import com.trollworks.toolkit.utility.cmdline.CmdLine;
import com.trollworks.toolkit.utility.cmdline.CmdLineOption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Locates and optionally deletes duplicate files in a file system hierarchy. */
public class FindDuplicates implements FileScanner.Handler {
	@Localize("Examined %,d files, containing %,d bytes.")
	private static String			PROGRESS;
	@Localize("\n%s %,d files that were duplicated, containing %,d bytes.\n")
	private static String			RESULT;
	@Localize("Discovered")
	private static String			DISCOVERED;
	@Localize("Removed")
	private static String			REMOVED;
	@Localize("%s is a duplicate of %s\n")
	private static String			DUPLICATE_OF;
	@Localize(" ** DELETED %s\n")
	private static String			DELETED;
	@Localize("Delete all duplicates found.")
	private static String			DELETE_DESCRIPTION;
	@Localize("Process files and directories that start with a dot (.). These 'hidden' files are ignored by default.")
	private static String			INCLUDE_HIDDEN_DESCRIPTION;
	@Localize("Each directory specified on the command line will be be scanned for duplicates.")
	private static String			HELP_HEADER;
	@Localize("Limit processing to just files with the specified extension. May be specified more than once.")
	private static String			EXTENSION_DESCRIPTION;
	@Localize("EXTENSION")
	private static String			EXTENSION_ARG;
	@Localize("** Skipping. Not a directory: %s\n")
	private static String			NOT_A_DIRECTORY;
	@Localize("** Skipping. Does not exist: %s\n")
	private static String			DOESNT_EXIST;

	static {
		Localization.initialize();
	}

	private Set<String>				mExtensions		= new HashSet<>();
	private boolean					mDelete;
	private HashMap<String, Path>	mHashToPathMap	= new HashMap<>();
	private int						mProcessedCount;
	private long					mBytesProcessed;
	private int						mDuplicateCount;
	private long					mBytesDuplicated;
	private int						mLastProgressLength;
	private long					mLastProgress;

	public static void main(String[] args) {
		CmdLineOption extensionOption = new CmdLineOption(EXTENSION_DESCRIPTION, EXTENSION_ARG, "extension"); //$NON-NLS-1$
		CmdLineOption includeHiddenOption = new CmdLineOption(INCLUDE_HIDDEN_DESCRIPTION, null, "hidden"); //$NON-NLS-1$
		CmdLineOption deleteOption = new CmdLineOption(DELETE_DESCRIPTION, null, "d", "delete"); //$NON-NLS-1$ //$NON-NLS-2$
		CmdLine cmdLine = new CmdLine();
		cmdLine.addOptions(extensionOption, includeHiddenOption, deleteOption);
		cmdLine.setHelpHeader(HELP_HEADER);
		cmdLine.processArguments(args);
		boolean hidden = cmdLine.isOptionUsed(includeHiddenOption);
		boolean delete = cmdLine.isOptionUsed(deleteOption);
		FindDuplicates handler = new FindDuplicates(cmdLine.getOptionArguments(extensionOption), delete);
		List<String> paths = cmdLine.getArguments();
		if (paths.isEmpty()) {
			cmdLine.showHelpAndExit();
		} else {
			for (String one : paths) {
				Path path = Paths.get(one);
				if (Files.isDirectory(path)) {
					FileScanner.walk(path, handler, !hidden);
				} else if (Files.exists(path)) {
					System.out.printf(NOT_A_DIRECTORY, path);
				} else {
					System.out.printf(DOESNT_EXIST, path);
				}
			}
			handler.eraseLastProgress();
			System.out.println();
			System.out.printf(PROGRESS, Integer.valueOf(handler.getProcessedCount()), Long.valueOf(handler.getBytesProcessed()));
			System.out.printf(RESULT, delete ? REMOVED : DISCOVERED, Integer.valueOf(handler.getDuplicateCount()), Long.valueOf(handler.getBytesDuplicated()));
			System.out.println();
		}
	}

	private FindDuplicates(List<String> extensions, boolean delete) {
		if (extensions != null) {
			for (String one : extensions) {
				mExtensions.add((one.startsWith(".") ? one.substring(1) : one).toLowerCase()); //$NON-NLS-1$
			}
		}
		mDelete = delete;
	}

	public int getProcessedCount() {
		return mProcessedCount;
	}

	public long getBytesProcessed() {
		return mBytesProcessed;
	}

	public int getDuplicateCount() {
		return mDuplicateCount;
	}

	public long getBytesDuplicated() {
		return mBytesDuplicated;
	}

	public void eraseLastProgress() {
		for (int i = 0; i < mLastProgressLength; i++) {
			System.out.print("\b \b"); //$NON-NLS-1$
		}
		System.out.flush();
		mLastProgressLength = 0;
	}

	@Override
	public void processFile(Path path) throws IOException {
		if (mExtensions.isEmpty() || mExtensions.contains(PathUtils.getExtension(path).toLowerCase())) {
			byte[] buffer = new byte[8192];
			MessageDigest sha1;
			try {
				sha1 = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
			} catch (NoSuchAlgorithmException exception) {
				throw new IOException(exception);
			}
			try (InputStream in = Files.newInputStream(path)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					sha1.update(buffer, 0, read);
				}
			}
			long fileSize = Files.size(path);
			mProcessedCount++;
			mBytesProcessed += fileSize;
			String hash = Text.bytesToHex(sha1.digest());
			if (mHashToPathMap.containsKey(hash)) {
				mDuplicateCount++;
				mBytesDuplicated += fileSize;
				eraseLastProgress();
				System.out.printf(DUPLICATE_OF, path, mHashToPathMap.get(hash));
				if (mDelete) {
					Files.delete(path);
					System.out.printf(DELETED, path);
				}
			} else {
				mHashToPathMap.put(hash, path);
			}
			long now = System.currentTimeMillis();
			if (now - mLastProgress > 1000) {
				eraseLastProgress();
				String progress = String.format(PROGRESS, Integer.valueOf(mProcessedCount), Long.valueOf(mBytesProcessed));
				mLastProgressLength = progress.length();
				mLastProgress = now;
				System.out.print(progress);
				System.out.flush();
			}
		}
	}
}
