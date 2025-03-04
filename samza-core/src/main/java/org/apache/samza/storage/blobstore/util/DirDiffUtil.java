/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.storage.blobstore.util;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.SamzaException;
import org.apache.samza.storage.blobstore.diff.DirDiff;
import org.apache.samza.storage.blobstore.index.DirIndex;
import org.apache.samza.storage.blobstore.index.FileIndex;
import org.apache.samza.storage.blobstore.index.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Provides helper methods to create a {@link DirDiff} between local and remote snapshots.
 */
public class DirDiffUtil {
  private static final Logger LOG = LoggerFactory.getLogger(DirDiffUtil.class);
  public static final int CACHE_SIZE = 10;
  /**
   * Checks if a local directory and a remote directory are identical. Local and remote directories are identical iff:
   * 1. The local directory has exactly the same set of files as the remote directory, and the files are themselves
   * identical, as determined by {@link #areSameFile(boolean, boolean)}, except for those allowed to differ according to
   * {@code filesToIgnore}.
   * 2. The local directory has exactly the same set of sub-directories as the remote directory.
   *
   * @param filesToIgnore a set of file names to ignore during the directory comparisons
   *                      (does not exclude directory names)
   * @param compareLargeFileChecksums whether to compare checksums for large files (&gt; 1 MB).
   * @param compareFileOwners whether to compare file owners
   * @return boolean indicating whether the local and remote directory are identical.
   */
  // TODO HIGH shesharm add unit tests
  public BiPredicate<File, DirIndex> areSameDir(Set<String> filesToIgnore, boolean compareLargeFileChecksums, boolean compareFileOwners) {
    return (localDir, remoteDir) -> {
      String remoteDirName = remoteDir.getDirName().equals(DirIndex.ROOT_DIR_NAME) ? "root" : remoteDir.getDirName();
      LOG.debug("Creating diff between local dir: {} and remote dir: {} for comparison.",
          localDir.getAbsolutePath(), remoteDirName);
      DirDiff dirDiff = DirDiffUtil.getDirDiff(localDir, remoteDir, DirDiffUtil.areSameFile(compareLargeFileChecksums, compareFileOwners));

      boolean areSameDir = true;
      List<String> filesRemoved = dirDiff.getFilesRemoved().stream()
          .map(FileIndex::getFileName)
          .filter(name -> !filesToIgnore.contains(name))
          .collect(Collectors.toList());

      if (!filesRemoved.isEmpty()) {
        areSameDir = false;
        LOG.error("Local directory: {} is missing files that are present in remote snapshot: {}",
            localDir.getAbsolutePath(), StringUtils.join(filesRemoved, ", "));
      }

      List<DirIndex> subDirsRemoved = dirDiff.getSubDirsRemoved();
      if (!subDirsRemoved.isEmpty()) {
        areSameDir = false;
        List<String> missingSubDirs = subDirsRemoved.stream().map(DirIndex::getDirName).collect(Collectors.toList());
        LOG.error("Local directory: {} is missing sub-dirs that are present in remote snapshot: {}",
            localDir.getAbsolutePath(), StringUtils.join(missingSubDirs, ", "));
      }

      List<String> filesAdded = dirDiff.getFilesAdded().stream()
          .map(File::getName)
          .filter(name -> !filesToIgnore.contains(name))
          .collect(Collectors.toList());
      if (!filesAdded.isEmpty()) {
        areSameDir = false;
        LOG.error("Local directory: {} has additional files that are not present in remote snapshot: {}",
            localDir.getAbsolutePath(), StringUtils.join(filesAdded, ", "));
      }

      List<DirDiff> subDirsAdded = dirDiff.getSubDirsAdded();
      if (!subDirsAdded.isEmpty()) {
        areSameDir = false;
        List<String> addedDirs = subDirsAdded.stream().map(DirDiff::getDirName).collect(Collectors.toList());
        LOG.error("Local directory: {} has additional sub-dirs that are not present in remote snapshot: {}",
            localDir.getAbsolutePath(), StringUtils.join(addedDirs, ", "));
      }

      // dir diff calculation already ensures that all retained files are equal (by definition)
      // recursively test that all retained sub-dirs are equal as well
      Map<String, DirIndex> remoteSubDirs = new HashMap<>();
      for (DirIndex subDir: remoteDir.getSubDirsPresent()) {
        remoteSubDirs.put(subDir.getDirName(), subDir);
      }
      for (DirDiff subDirRetained: dirDiff.getSubDirsRetained()) {
        String localSubDirName = subDirRetained.getDirName();
        File localSubDirFile = Paths.get(localDir.getAbsolutePath(), localSubDirName).toFile();
        DirIndex remoteSubDir = remoteSubDirs.get(localSubDirName);
        boolean areSameSubDir = areSameDir(filesToIgnore, false, compareFileOwners).test(localSubDirFile, remoteSubDir);
        if (!areSameSubDir) {
          LOG.debug("Local sub-dir: {} and remote sub-dir: {} are not same.",
              localSubDirFile.getAbsolutePath(), remoteSubDir.getDirName());
          areSameDir = false;
        }
      }

      LOG.debug("Local dir: {} and remote dir: {} are {}the same.",
          localDir.getAbsolutePath(), remoteDirName, areSameDir ? "" : "not ");
      return areSameDir;
    };
  }

  /**
   * Bipredicate to test a local file in the filesystem and a remote file {@link FileIndex} and find out if they represent
   * the same file. Files with same attributes as well as content are same file. A SST file in a special case. They are
   * immutable, so we only compare their attributes but not the content.
   * @param compareLargeFileChecksums whether to compare checksums for large files (&gt; 1 MB).
   * @param compareFileOwners whether to compare owners of the files. Useful when migrating an exiting job to new machine(s)
   *                          that may have different owner ids.
   * @return BiPredicate to test similarity of local and remote files
   */
  public static BiPredicate<File, FileIndex> areSameFile(boolean compareLargeFileChecksums, boolean compareFileOwners) {

    // Cache owner/group names to reduce calls to sun.nio.fs.UnixFileAttributes.group
    Cache<String, String> groupCache = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();
    // Cache owner/group names to reduce calls to sun.nio.fs.UnixFileAttributes.owner
    Cache<String, String> ownerCache = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();

    return (localFile, remoteFile) -> {
      if (localFile.getName().equals(remoteFile.getFileName())) {
        FileMetadata remoteFileMetadata = remoteFile.getFileMetadata();

        boolean areSameFiles;
        PosixFileAttributes localFileAttrs;
        try {
          localFileAttrs = Files.readAttributes(localFile.toPath(), PosixFileAttributes.class);

          // Don't compare file timestamps. The ctime of a local file just restored will be different than the
          // remote file, and will cause the file to be uploaded again during the first commit after restore.
          areSameFiles = localFileAttrs.size() == remoteFileMetadata.getSize();
          // In case a job is moved to a new cluster/machine, the owners (gid/uid) may be different than the one present
          // in the remote snapshot. This flag indicates if we should compare it at all.
          if (compareFileOwners) {
            LOG.trace("Comparing owners of remote and local copy of file {}", localFile.getAbsolutePath());
            areSameFiles =
                areSameFiles && groupCache.get(String.valueOf(Files.getAttribute(localFile.toPath(), "unix:gid")),
                  () -> localFileAttrs.group().getName()).equals(remoteFileMetadata.getGroup()) && ownerCache.get(
                       String.valueOf(Files.getAttribute(localFile.toPath(), "unix:uid")),
                    () -> localFileAttrs.owner().getName()).equals(remoteFileMetadata.getOwner());
          }

        } catch (IOException | ExecutionException e) {
          LOG.error("Error reading attributes for file: {}", localFile.getAbsolutePath());
          throw new RuntimeException(String.format("Error reading attributes for file: %s", localFile.getAbsolutePath()));
        }

        Set<PosixFilePermission> remoteFilePermissions =
            PosixFilePermissions.fromString(remoteFileMetadata.getPermissions());

        // only verify permissions for file owner
        areSameFiles = areSameFiles &&
            localFileAttrs.permissions().contains(PosixFilePermission.OWNER_READ) ==
                remoteFilePermissions.contains(PosixFilePermission.OWNER_READ) &&
            localFileAttrs.permissions().contains(PosixFilePermission.OWNER_WRITE) ==
                remoteFilePermissions.contains(PosixFilePermission.OWNER_WRITE) &&
            localFileAttrs.permissions().contains(PosixFilePermission.OWNER_EXECUTE) ==
                remoteFilePermissions.contains(PosixFilePermission.OWNER_EXECUTE);

        if (!areSameFiles) {
          if (LOG.isWarnEnabled()) {
            LOG.warn("Local file: {} and remote file: {} are not same. "
                    + "Local file attributes: {}. Remote file attributes: {}.", localFile.getAbsolutePath(), remoteFile.getFileName(),
                fileAttributesToString(localFileAttrs), remoteFile.getFileMetadata().toString());
          }
          return false;
        } else {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Local file: {}. Remote file: {}. " + "Local file attributes: {}. Remote file attributes: {}.",
                localFile.getAbsolutePath(), remoteFile.getFileName(), fileAttributesToString(localFileAttrs),
                remoteFile.getFileMetadata().toString());
          }
        }

        boolean isLargeFile = localFileAttrs.size() > 1024 * 1024;
        if (!compareLargeFileChecksums && isLargeFile) {
          // Since RocksDB SST files are immutable after creation, we can skip the expensive checksum computations
          // which requires reading the entire file.
          LOG.debug("Local file: {} and remote file: {} are same. " +
                  "Skipping checksum calculation for large file of size: {}.",
              localFile.getAbsolutePath(), remoteFile.getFileName(), localFileAttrs.size());
          return true;
        } else {
          try {
            FileInputStream fis = new FileInputStream(localFile);
            CheckedInputStream cis = new CheckedInputStream(fis, new CRC32());
            byte[] buffer = new byte[8 * 1024]; // 8 KB
            while (cis.read(buffer, 0, buffer.length) >= 0) { }
            long localFileChecksum = cis.getChecksum().getValue();
            cis.close();

            boolean areSameChecksum = localFileChecksum == remoteFile.getChecksum();
            if (!areSameChecksum) {
              LOG.warn("Local file: {} and remote file: {} are not same. " +
                      "Local checksum: {}. Remote checksum: {}",
                  localFile.getAbsolutePath(), remoteFile.getFileName(), localFileChecksum, remoteFile.getChecksum());
            } else {
              LOG.debug("Local file: {} and remote file: {} are same. Local checksum: {}. Remote checksum: {}",
                  localFile.getAbsolutePath(), remoteFile.getFileName(), localFileChecksum, remoteFile.getChecksum());
            }
            return areSameChecksum;
          } catch (IOException e) {
            throw new SamzaException("Error calculating checksum for local file: " + localFile.getAbsolutePath(), e);
          }
        }
      }

      return false;
    };
  }

  /**
   * Compare the local snapshot directory and the remote snapshot directory and return the recursive diff of the two as
   * a {@link DirDiff}.
   * @param localSnapshotDir File representing local snapshot root directory
   * @param remoteSnapshotDir {@link DirIndex} representing the remote snapshot directory
   * @param areSameFile A BiPredicate to test if a local and remote file are the same file
   * @return {@link DirDiff} representing the recursive diff of local and remote snapshots directories
   */
  public static DirDiff getDirDiff(File localSnapshotDir, DirIndex remoteSnapshotDir,
      BiPredicate<File, FileIndex> areSameFile) {
    return getDirDiff(localSnapshotDir, remoteSnapshotDir, areSameFile, true);
  }

  private static DirDiff getDirDiff(File localSnapshotDir, DirIndex remoteSnapshotDir,
      BiPredicate<File, FileIndex> areSameFile, boolean isRootDir) {
    Preconditions.checkState(localSnapshotDir != null && localSnapshotDir.isDirectory());
    Preconditions.checkNotNull(remoteSnapshotDir);

    LOG.debug("Creating DirDiff between local dir: {} and remote dir: {}",
        localSnapshotDir.getPath(), remoteSnapshotDir.getDirName());
    List<DirDiff> subDirsAdded = new ArrayList<>();
    List<DirDiff> subDirsRetained = new ArrayList<>();
    List<DirIndex> subDirsRemoved = new ArrayList<>();
    List<File> filesToUpload = new ArrayList<>();
    List<FileIndex> filesToRetain = new ArrayList<>();
    List<FileIndex> filesToRemove = new ArrayList<>();

    // list files returns empty list if local snapshot directory is empty
    List<File> localSnapshotFiles = Arrays.asList(Objects.requireNonNull(localSnapshotDir.listFiles(File::isFile)));
    List<FileIndex> remoteSnapshotFiles = remoteSnapshotDir.getFilesPresent();

    // list files returns empty list if local snapshot directory is empty
    List<File> localSnapshotSubDirs = Arrays.asList(Objects.requireNonNull(localSnapshotDir.listFiles(File::isDirectory)));
    Set<String> localSnapshotSubDirNames = localSnapshotSubDirs.stream()
        .map(File::getName)
        .collect(Collectors.toCollection(HashSet::new));

    List<DirIndex> remoteSnapshotSubDirs = remoteSnapshotDir.getSubDirsPresent();
    Set<String> remoteSnapshotSubDirNames = remoteSnapshotSubDirs.stream()
        .map(DirIndex::getDirName)
        .collect(Collectors.toCollection(HashSet::new));

    Map<String, FileIndex> remoteFiles = remoteSnapshotFiles.stream()
        .collect(Collectors.toMap(FileIndex::getFileName, Function.identity()));

    Map<String, File> localFiles = localSnapshotFiles.stream()
        .collect(Collectors.toMap(File::getName, Function.identity()));

    for (String file : Sets.union(remoteFiles.keySet(), localFiles.keySet())) {
      if (localFiles.containsKey(file)) {
        if (remoteFiles.containsKey(file)) {
          if (areSameFile.test(localFiles.get(file), remoteFiles.get(file))) {
            // Files are the same locally and remotely, Retain
            filesToRetain.add(remoteFiles.get(file));
          } else {
            // Files are not the same, remove and upload
            filesToRemove.add(remoteFiles.get(file));
            filesToUpload.add(localFiles.get(file));
          }
        } else {
          // File exists locally, but not remotely, Upload
          filesToUpload.add(localFiles.get(file));
        }
      } else if (remoteFiles.containsKey(file)) {
        // File exists remotely, but not locally,  Remove
        filesToRemove.add(remoteFiles.get(file));
      }
    }

    for (File localSnapshotSubDir: localSnapshotSubDirs) {
      if (!remoteSnapshotSubDirNames.contains(localSnapshotSubDir.getName())) {
        LOG.debug("Subdir {} present in local snapshot but not in remote snapshot. " +
            "Recursively adding subdir contents.", localSnapshotSubDir.getPath());
        subDirsAdded.add(getDiffForNewDir(localSnapshotSubDir));
      } else {
        LOG.debug("Subdir {} present in local snapshot and in remote snapshot. " +
            "Recursively comparing local and remote subdirs.", localSnapshotSubDir.getPath());
        DirIndex remoteSubDirIndex =
            remoteSnapshotSubDirs.stream()
                .filter(indexBlob -> indexBlob.getDirName().equals(localSnapshotSubDir.getName()))
                .findFirst().get();
        subDirsRetained.add(getDirDiff(localSnapshotSubDir, remoteSubDirIndex, areSameFile, false));
      }
    }

    // 3. Subdir in remote snapshot but not in local snapshot
    for (DirIndex remoteSnapshotSubDir: remoteSnapshotSubDirs) {
      if (!localSnapshotSubDirNames.contains(remoteSnapshotSubDir.getDirName())) {
        LOG.debug("Subdir {} present in remote snapshot but not in local snapshot. " +
            "Marking for removal from remote snapshot. ", remoteSnapshotDir.getDirName());
        subDirsRemoved.add(remoteSnapshotSubDir);
      }
    }

    String dirName = isRootDir ? DirIndex.ROOT_DIR_NAME : localSnapshotDir.getName();
    return new DirDiff(dirName,
        filesToUpload, filesToRetain, filesToRemove,
        subDirsAdded, subDirsRetained, subDirsRemoved);
  }

  /**
   * Builds a {@link DirDiff} from a new local directory that is not already present in the remote snapshot.
   * @param localSubDir File representing the local directory to create the new {@link DirDiff} for.
   */
  private static DirDiff getDiffForNewDir(File localSubDir) {
    List<File> filesAdded = new ArrayList<>();
    List<DirDiff> subDirsAdded = new ArrayList<>();

    File[] files = localSubDir.listFiles();
    if (files != null) {
      for (File file: files) {
        if (file.isFile()) {
          LOG.debug("Adding file {} to local sub dir {}", file.getName(), localSubDir.getPath());
          filesAdded.add(file);
        } else {
          LOG.debug("Adding sub dir {} to sub dir {}", file.getName(), localSubDir.getPath());
          subDirsAdded.add(getDiffForNewDir(file));
        }
      }
    }

    return new DirDiff(localSubDir.getName(), filesAdded, Collections.emptyList(), Collections.emptyList(),
        subDirsAdded, Collections.emptyList(), Collections.emptyList());
  }

  private static String fileAttributesToString(PosixFileAttributes fileAttributes) {
    return "PosixFileAttributes{" +
        "creationTimeMillis=" + fileAttributes.creationTime().toMillis() +
        ", lastModifiedTimeMillis=" + fileAttributes.lastModifiedTime().toMillis() +
        ", size=" + fileAttributes.size() +
        ", owner='" + fileAttributes.owner() + '\'' +
        ", group='" + fileAttributes.group() + '\'' +
        ", permissions=" + PosixFilePermissions.toString(fileAttributes.permissions()) +
        '}';
  }
}
