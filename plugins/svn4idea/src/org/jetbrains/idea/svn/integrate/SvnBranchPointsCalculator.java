/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.integrate;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ThreadSafeTransparentlyFailedValue;
import com.intellij.openapi.vcs.changes.TransparentlyFailedValueI;
import com.intellij.openapi.vcs.persistent.SmallMapSerializer;
import com.intellij.util.Consumer;
import com.intellij.util.ThrowableConvertor;
import com.intellij.util.continuation.ContinuationContext;
import com.intellij.util.continuation.TaskDescriptor;
import com.intellij.util.continuation.Where;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.SvnVcs;
import org.jetbrains.idea.svn.history.CopyData;
import org.jetbrains.idea.svn.history.FirstInBranch;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class SvnBranchPointsCalculator {

  private static final Logger LOG = Logger.getInstance(SvnBranchPointsCalculator.class);

  private SmallMapSerializer<String, TreeMap<String, BranchCopyData>> myPersistentMap;
  @NotNull private final Object myPersistenceLock = new Object();
  private File myFile;
  private final Project myProject;

  public SvnBranchPointsCalculator(final Project project) {
    myProject = project;
    final File vcs = new File(PathManager.getSystemPath(), "vcs");
    File file = new File(vcs, "svn_copy_sources");
    file.mkdirs();
    myFile = file;
    myFile = new File(file, project.getLocationHash());
  }

  public void activate() {
    synchronized (myPersistenceLock) {
      myPersistentMap = new SmallMapSerializer<>(myFile, EnumeratorStringDescriptor.INSTANCE, new BranchDataExternalizer());
    }
  }

  @Nullable
  public WrapperInvertor getBestHit(String repoUrl, String source, String target) {
    synchronized (myPersistenceLock) {
      WrapperInvertor result = null;
      TreeMap<String, BranchCopyData> map = myPersistentMap.get(repoUrl);

      if (map != null) {
        BranchCopyData sourceData = getBranchData(map, source);
        BranchCopyData targetData = getBranchData(map, target);

        if (sourceData != null && targetData != null) {
          boolean inverted = sourceData.getTargetRevision() > targetData.getTargetRevision();
          result = new WrapperInvertor(inverted, inverted ? sourceData : targetData);
        }
        else if (sourceData != null) {
          result = new WrapperInvertor(true, sourceData);
        }
        else if (targetData != null) {
          result = new WrapperInvertor(false, targetData);
        }
      }

      logCopyData(repoUrl, source, target, result);

      return result;
    }
  }

  public void deactivate() {
    synchronized (myPersistenceLock) {
      myPersistentMap.force();
      myPersistentMap = null;
    }
  }

  private void persist(String uid, @NotNull BranchCopyData data) {
    // todo - rewrite of rather big piece; consider rewriting
    synchronized (myPersistenceLock) {
      TreeMap<String, BranchCopyData> map = myPersistentMap.get(uid);
      if (map == null) {
        map = new TreeMap<>();
      }
      map.put(data.getTarget(), data);
      myPersistentMap.put(uid, map);
      myPersistentMap.force();
    }
  }

  @Nullable
  private static BranchCopyData getBranchData(@NotNull NavigableMap<String, BranchCopyData> map, String url) {
    Map.Entry<String, BranchCopyData> branchData = map.floorEntry(url);
    return branchData != null && url.startsWith(branchData.getKey()) ? branchData.getValue() : null;
  }

  private static class BranchDataExternalizer implements DataExternalizer<TreeMap<String, BranchCopyData>> {
    public void save(@NotNull DataOutput out, TreeMap<String, BranchCopyData> value) throws IOException {
      out.writeInt(value.size());
      for (Map.Entry<String, BranchCopyData> entry : value.entrySet()) {
        out.writeUTF(entry.getKey());
        final BranchCopyData entryValue = entry.getValue();
        out.writeUTF(entryValue.getSource());
        out.writeUTF(entryValue.getTarget());
        out.writeLong(entryValue.getSourceRevision());
        out.writeLong(entryValue.getTargetRevision());
      }
    }

    public TreeMap<String, BranchCopyData> read(@NotNull DataInput in) throws IOException {
      final TreeMap<String, BranchCopyData> result = new TreeMap<>();

      final int num = in.readInt();
      for (int i = 0; i < num; i++) {
        final String key = in.readUTF();
        final String source = in.readUTF();
        final String target = in.readUTF();
        final long sourceRevision = in.readLong();
        final long targetRevision = in.readLong();

        result.put(key, new BranchCopyData(source, sourceRevision, target, targetRevision));
      }
      return result;
    }
  }

  public static class WrapperInvertor {
    private final BranchCopyData myWrapped;
    private final boolean myInvertedSense;

    public WrapperInvertor(boolean invertedSense, BranchCopyData wrapped) {
      myInvertedSense = invertedSense;
      myWrapped = wrapped;
    }

    public boolean isInvertedSense() {
      return myInvertedSense;
    }

    public BranchCopyData getWrapped() {
      return myWrapped;
    }

    public BranchCopyData getTrue() {
      return myInvertedSense ? myWrapped.invertSelf() : myWrapped;
    }

    public BranchCopyData inverted() {
      return myWrapped.invertSelf();
    }

    @Override
    public String toString() {
      return "inverted: " + myInvertedSense + " wrapped: " + myWrapped.toString();
    }
  }

  private static class Loader implements ThrowableConvertor<KeyData, WrapperInvertor, VcsException> {
    private SvnVcs myVcs;

    private Loader(final Project project) {
      myVcs = SvnVcs.getInstance(project);
    }

    @Override
    public WrapperInvertor convert(final KeyData keyData) throws VcsException {
      WrapperInvertor result = null;
      CopyData copyData = new FirstInBranch(myVcs, keyData.getRepoUrl(), keyData.getTargetUrl(), keyData.getSourceUrl()).run();

      if (copyData != null) {
        final boolean correct = copyData.isTrunkSupposedCorrect();
        final BranchCopyData branchCopyData;
        if (correct) {
          branchCopyData = new BranchCopyData(keyData.getSourceUrl(), copyData.getCopySourceRevision(), keyData.getTargetUrl(),
                                              copyData.getCopyTargetRevision());
        }
        else {
          branchCopyData = new BranchCopyData(keyData.getTargetUrl(), copyData.getCopySourceRevision(), keyData.getSourceUrl(),
                                              copyData.getCopyTargetRevision());
        }
        result = new WrapperInvertor(!correct, branchCopyData);
      }

      logCopyData(keyData.getRepoUrl(), keyData.getSourceUrl(), keyData.getTargetUrl(), result);

      return result;
    }
  }

  private static void logCopyData(@NotNull String repoUrl,
                                  @NotNull String sourceUrl,
                                  @NotNull String targetUrl,
                                  @Nullable WrapperInvertor inverter) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("repoURL: " + repoUrl + ", sourceUrl:" + sourceUrl + ", targetUrl: " + targetUrl + ", inverter: " + inverter);
    }
  }

  private static class KeyData {
    private final String myRepoUrl;
    private final String mySourceUrl;
    private final String myTargetUrl;

    public KeyData(final String repoUID, final String sourceUrl, final String targetUrl) {
      myRepoUrl = repoUID;
      mySourceUrl = sourceUrl;
      myTargetUrl = targetUrl;
    }

    public String getRepoUrl() {
      return myRepoUrl;
    }

    public String getSourceUrl() {
      return mySourceUrl;
    }

    public String getTargetUrl() {
      return myTargetUrl;
    }

    @Override
    public String toString() {
      return "repoURL: " + myRepoUrl + " sourceUrl:" + mySourceUrl + " targetUrl: " + myTargetUrl;
    }
  }

  public static class BranchCopyData {
    private final String mySource;
    private final String myTarget;
    private final long mySourceRevision;
    private final long myTargetRevision;

    public BranchCopyData(String source, long sourceRevision, String target, long targetRevision) {
      mySource = source;
      mySourceRevision = sourceRevision;
      myTarget = target;
      myTargetRevision = targetRevision;
    }

    @Override
    public String toString() {
      return "source: " + mySource + "@" + mySourceRevision + " target: " + myTarget + "@" + myTargetRevision;
    }

    public String getSource() {
      return mySource;
    }

    public long getSourceRevision() {
      return mySourceRevision;
    }

    public String getTarget() {
      return myTarget;
    }

    public long getTargetRevision() {
      return myTargetRevision;
    }

    public BranchCopyData invertSelf() {
      return new BranchCopyData(myTarget, myTargetRevision, mySource, mySourceRevision);
    }
  }

  public TaskDescriptor getFirstCopyPointTask(final String repoUID, final String sourceUrl, final String targetUrl,
                                              final Consumer<TransparentlyFailedValueI<WrapperInvertor, VcsException>> consumer) {
    KeyData in = new KeyData(repoUID, sourceUrl, targetUrl);
    TransparentlyFailedValueI<WrapperInvertor, VcsException> value = new ThreadSafeTransparentlyFailedValue<>();

    final TaskDescriptor pooled = new TaskDescriptor("Looking for branch origin", Where.POOLED) {
      @Override
      public void run(ContinuationContext context) {
        try {
          WrapperInvertor calculatedValue = new Loader(myProject).convert(in);
          if (calculatedValue != null) {
            persist(repoUID, calculatedValue.getWrapped());
          }
          value.set(calculatedValue);
        }
        catch (Exception e) {
          setException(value, e);
        }
        context.next(new TaskDescriptor("final part", Where.AWT) {
          @Override
          public void run(ContinuationContext context) {
            consumer.consume(value);
          }
        });
      }
    };

    return new TaskDescriptor("short part", Where.AWT) {
      @Override
      public void run(ContinuationContext context) {
        try {
          value.set(getBestHit(repoUID, sourceUrl, targetUrl));
        }
        catch (Exception e) {
          setException(value, e);
        }
        if (value.haveSomething()) {
          consumer.consume(value);
          return;
        }
        context.next(pooled);
      }
    };
  }

  private static void setException(TransparentlyFailedValueI<WrapperInvertor, VcsException> value, Exception e) {
    if (e instanceof VcsException) {
      value.fail((VcsException)e);
    }
    else {
      LOG.info(e);
      value.failRuntime(e instanceof RuntimeException ? (RuntimeException)e : new RuntimeException(e));
    }
  }
}
