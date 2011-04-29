/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.parse.SplitSample;
import org.apache.hadoop.hive.ql.plan.PartitionDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.shims.HadoopShims.CombineFileInputFormatShim;
import org.apache.hadoop.hive.shims.HadoopShims.InputSplitShim;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;

/**
 * CombineHiveInputFormat is a parameterized InputFormat which looks at the path
 * name and determine the correct InputFormat for that path name from
 * mapredPlan.pathToPartitionInfo(). It can be used to read files with different
 * input format in the same map-reduce job.
 */
public class CombineHiveInputFormat<K extends WritableComparable, V extends Writable>
    extends HiveInputFormat<K, V> {

  public static final Log LOG = LogFactory
      .getLog("org.apache.hadoop.hive.ql.io.CombineHiveInputFormat");

  /**
   * CombineHiveInputSplit encapsulates an InputSplit with its corresponding
   * inputFormatClassName. A CombineHiveInputSplit comprises of multiple chunks
   * from different files. Since, they belong to a single directory, there is a
   * single inputformat for all the chunks.
   */
  public static class CombineHiveInputSplit implements InputSplitShim {

    String inputFormatClassName;
    InputSplitShim inputSplitShim;

    public CombineHiveInputSplit() throws IOException {
      this(ShimLoader.getHadoopShims().getCombineFileInputFormat()
          .getInputSplitShim());
    }

    public CombineHiveInputSplit(InputSplitShim inputSplitShim) throws IOException {
      this(inputSplitShim.getJob(), inputSplitShim);
    }

    public CombineHiveInputSplit(JobConf job, InputSplitShim inputSplitShim)
        throws IOException {
      this.inputSplitShim = inputSplitShim;
      if (job != null) {
        Map<String, PartitionDesc> pathToPartitionInfo = Utilities
            .getMapRedWork(job).getPathToPartitionInfo();

        // extract all the inputFormatClass names for each chunk in the
        // CombinedSplit.
        Path[] ipaths = inputSplitShim.getPaths();
        if (ipaths.length > 0) {
          PartitionDesc part = HiveFileFormatUtils
              .getPartitionDescFromPathRecursively(pathToPartitionInfo,
                  ipaths[0], IOPrepareCache.get().getPartitionDescMap());
          inputFormatClassName = part.getInputFileFormatClass().getName();
        }
      }
    }

    public InputSplitShim getInputSplitShim() {
      return inputSplitShim;
    }

    /**
     * Returns the inputFormat class name for the i-th chunk.
     */
    public String inputFormatClassName() {
      return inputFormatClassName;
    }

    public void setInputFormatClassName(String inputFormatClassName) {
      this.inputFormatClassName = inputFormatClassName;
    }

    public JobConf getJob() {
      return inputSplitShim.getJob();
    }

    public long getLength() {
      return inputSplitShim.getLength();
    }

    /** Returns an array containing the startoffsets of the files in the split. */
    public long[] getStartOffsets() {
      return inputSplitShim.getStartOffsets();
    }

    /** Returns an array containing the lengths of the files in the split. */
    public long[] getLengths() {
      return inputSplitShim.getLengths();
    }

    /** Returns the start offset of the i<sup>th</sup> Path. */
    public long getOffset(int i) {
      return inputSplitShim.getOffset(i);
    }

    /** Returns the length of the i<sup>th</sup> Path. */
    public long getLength(int i) {
      return inputSplitShim.getLength(i);
    }

    /** Returns the number of Paths in the split. */
    public int getNumPaths() {
      return inputSplitShim.getNumPaths();
    }

    /** Returns the i<sup>th</sup> Path. */
    public Path getPath(int i) {
      return inputSplitShim.getPath(i);
    }

    /** Returns all the Paths in the split. */
    public Path[] getPaths() {
      return inputSplitShim.getPaths();
    }

    /** Returns all the Paths where this input-split resides. */
    public String[] getLocations() throws IOException {
      return inputSplitShim.getLocations();
    }

    /**
     * Prints this obejct as a string.
     */
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(inputSplitShim.toString());
      sb.append("InputFormatClass: " + inputFormatClassName);
      sb.append("\n");
      return sb.toString();
    }

    /**
     * Writable interface.
     */
    public void readFields(DataInput in) throws IOException {
      inputSplitShim.readFields(in);
      inputFormatClassName = in.readUTF();
    }

    /**
     * Writable interface.
     */
    public void write(DataOutput out) throws IOException {
      inputSplitShim.write(out);

      if (inputFormatClassName == null) {
        Map<String, PartitionDesc> pathToPartitionInfo = Utilities
            .getMapRedWork(getJob()).getPathToPartitionInfo();

        // extract all the inputFormatClass names for each chunk in the
        // CombinedSplit.
        PartitionDesc part = HiveFileFormatUtils.getPartitionDescFromPathRecursively(pathToPartitionInfo,
            inputSplitShim.getPath(0), IOPrepareCache.get().getPartitionDescMap());

        // create a new InputFormat instance if this is the first time to see
        // this class
        inputFormatClassName = part.getInputFileFormatClass().getName();
      }

      out.writeUTF(inputFormatClassName);
    }

    @Override
    public void shrinkSplit(long length) {
      inputSplitShim.shrinkSplit(length);
    }
  }

  // Splits are not shared across different partitions with different input formats.
  // For example, 2 partitions (1 sequencefile and 1 rcfile) will have 2 different splits
  private static class CombinePathInputFormat {
    private final List<Operator<? extends Serializable>> opList;
    private final String inputFormatClassName;

    public CombinePathInputFormat(List<Operator<? extends Serializable>> opList,
                                  String inputFormatClassName) {
      this.opList = opList;
      this.inputFormatClassName = inputFormatClassName;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof CombinePathInputFormat) {
        CombinePathInputFormat mObj = (CombinePathInputFormat)o;
        if (mObj == null) {
          return false;
        }
        return opList.equals(mObj.opList) &&
          inputFormatClassName.equals(mObj.inputFormatClassName);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return (opList == null) ? 0 : opList.hashCode();
    }
  }

  /**
   * Create Hive splits based on CombineFileSplit.
   */
  @Override
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    init(job);
    Map<String, ArrayList<String>> pathToAliases = mrwork.getPathToAliases();
    Map<String, Operator<? extends Serializable>> aliasToWork =
      mrwork.getAliasToWork();
    CombineFileInputFormatShim combine = ShimLoader.getHadoopShims()
        .getCombineFileInputFormat();

    if (combine == null) {
      return super.getSplits(job, numSplits);
    }

    if (combine.getInputPathsShim(job).length == 0) {
      throw new IOException("No input paths specified in job");
    }
    ArrayList<InputSplit> result = new ArrayList<InputSplit>();

    // combine splits only from same tables and same partitions. Do not combine splits from multiple
    // tables or multiple partitions.
    Path[] paths = combine.getInputPathsShim(job);
    Map<CombinePathInputFormat, CombineFilter> poolMap =
      new HashMap<CombinePathInputFormat, CombineFilter>();
    Set<Path> poolSet = new HashSet<Path>();

    for (Path path : paths) {

      PartitionDesc part = HiveFileFormatUtils.getPartitionDescFromPathRecursively(
          pathToPartitionInfo, path, IOPrepareCache.get().allocatePartitionDescMap());
      TableDesc tableDesc = part.getTableDesc();
      if ((tableDesc != null) && tableDesc.isNonNative()) {
        return super.getSplits(job, numSplits);
      }

      // Use HiveInputFormat if any of the paths is not splittable
      Class inputFormatClass = part.getInputFileFormatClass();
      String inputFormatClassName = inputFormatClass.getName();
      InputFormat inputFormat = getInputFormatFromCache(inputFormatClass, job);

      // Since there is no easy way of knowing whether MAPREDUCE-1597 is present in the tree or not,
      // we use a configuration variable for the same
      if (this.mrwork != null && !this.mrwork.getHadoopSupportsSplittable()) {
        // The following code should be removed, once
        // https://issues.apache.org/jira/browse/MAPREDUCE-1597 is fixed.
        // Hadoop does not handle non-splittable files correctly for CombineFileInputFormat,
        // so don't use CombineFileInputFormat for non-splittable files
        FileSystem inpFs = path.getFileSystem(job);

        if (inputFormat instanceof TextInputFormat) {
          Queue<Path> dirs = new LinkedList<Path>();
          FileStatus fStats = inpFs.getFileStatus(path);

          // If path is a directory
          if (fStats.isDir()) {
            dirs.offer(path);
          }
          else if ((new CompressionCodecFactory(job)).getCodec(path) != null) {
            return super.getSplits(job, numSplits);
          }

          while (dirs.peek() != null) {
            Path tstPath = dirs.remove();
            FileStatus[] fStatus = inpFs.listStatus(tstPath);
            for (int idx = 0; idx < fStatus.length; idx++) {
              if (fStatus[idx].isDir()) {
                dirs.offer(fStatus[idx].getPath());
              }
              else if ((new CompressionCodecFactory(job)).getCodec(fStatus[idx].getPath()) != null) {
                return super.getSplits(job, numSplits);
              }
            }
          }
        }
      }

      if (inputFormat instanceof SymlinkTextInputFormat) {
        return super.getSplits(job, numSplits);
      }

      Path filterPath = path;

      // In the case of tablesample, the input paths are pointing to files rather than directories.
      // We need to get the parent directory as the filtering path so that all files in the same
      // parent directory will be grouped into one pool but not files from different parent
      // directories. This guarantees that a split will combine all files in the same partition
      // but won't cross multiple partitions if the user has asked so.
      if (mrwork.isMapperCannotSpanPartns() &&
          !path.getFileSystem(job).getFileStatus(path).isDir()) { // path is not directory
        filterPath = path.getParent();
      }

      // Does a pool exist for this path already
      CombineFilter f = null;
      List<Operator<? extends Serializable>> opList = null;
      boolean done = false;

      if (!mrwork.isMapperCannotSpanPartns()) {
        opList = HiveFileFormatUtils.doGetWorksFromPath(
                   pathToAliases, aliasToWork, filterPath);
        f = poolMap.get(new CombinePathInputFormat(opList, inputFormatClassName));
      }
      else {
        if (poolSet.contains(filterPath)) {
          LOG.info("CombineHiveInputSplit: pool is already created for " + path +
                   "; using filter path " + filterPath);
          done = true;
        }
        poolSet.add(filterPath);
      }

      if (!done) {
        if (f == null) {
          f = new CombineFilter(filterPath);
          LOG.info("CombineHiveInputSplit creating pool for " + path +
                   "; using filter path " + filterPath);
          combine.createPool(job, f);
          if (!mrwork.isMapperCannotSpanPartns()) {
            poolMap.put(new CombinePathInputFormat(opList, inputFormatClassName), f);
          }
        } else {
          LOG.info("CombineHiveInputSplit: pool is already created for " + path +
                   "; using filter path " + filterPath);
          f.addPath(filterPath);
        }
      }
    }

    InputSplitShim[] iss = combine.getSplits(job, 1);

    if (mrwork.getNameToSplitSample() != null && !mrwork.getNameToSplitSample().isEmpty()) {
      iss = sampleSplits(iss);
    }

    for (InputSplitShim is : iss) {
      CombineHiveInputSplit csplit = new CombineHiveInputSplit(job, is);
      result.add(csplit);
    }

    LOG.info("number of splits " + result.size());
    return result.toArray(new CombineHiveInputSplit[result.size()]);
  }

  /**
   * This function is used to sample inputs for clauses like "TABLESAMPLE(1 PERCENT)"
   *
   * First, splits are grouped by alias they are for. If one split serves more than one
   * alias or not for any sampled alias, we just directly add it to returned list.
   * Then we find a list of exclusive splits for every alias to be sampled.
   * For each alias, we start from position of seedNumber%totalNumber, and keep add
   * splits until the total size hits percentage.
   * @param splits
   * @return the sampled splits
   */
  private InputSplitShim[] sampleSplits(InputSplitShim[] splits) {
    HashMap<String, SplitSample> nameToSamples = mrwork.getNameToSplitSample();
    List<InputSplitShim> retLists = new ArrayList<InputSplitShim>();
    Map<String, ArrayList<InputSplitShim>> aliasToSplitList = new HashMap<String, ArrayList<InputSplitShim>>();
    Map<String, ArrayList<String>> pathToAliases = mrwork.getPathToAliases();

    // Populate list of exclusive splits for every sampled alias
    //
    for (InputSplitShim split : splits) {
      String alias = null;
      for (Path path : split.getPaths()) {
        List<String> l = HiveFileFormatUtils.doGetAliasesFromPath(
            pathToAliases, path);
        // a path for a split unqualified the split from being sampled if:
        // 1. it serves more than one alias
        // 2. the alias it serves is not sampled
        // 3. it serves different alias than another path for the same split
        if (l.size() != 1 || !nameToSamples.containsKey(l.get(0)) ||
            (alias != null && l.get(0) != alias)) {
          alias = null;
          break;
        }
        alias = l.get(0);
      }

      if (alias != null) {
        // split exclusively serves alias, which needs to be sampled
        // add it to the split list of the alias.
        if (!aliasToSplitList.containsKey(alias)) {
          aliasToSplitList.put(alias, new ArrayList<InputSplitShim>());
        }
        aliasToSplitList.get(alias).add(split);
      } else {
        // The split doesn't exclusively serve one alias
        retLists.add(split);
      }
    }

    // for every sampled alias, we figure out splits to be sampled and add
    // them to return list
    //
    for (Map.Entry<String, ArrayList<InputSplitShim>> entry: aliasToSplitList.entrySet()) {
      ArrayList<InputSplitShim> splitList = entry.getValue();
      long totalSize = 0;
      for (InputSplitShim split : splitList) {
        totalSize += split.getLength();
      }

      long targetSize = (long) (totalSize * nameToSamples.get(entry.getKey()).getPercent() / 100D);
      int startIndex = nameToSamples.get(entry.getKey()).getSeedNum() % splitList.size();
      int size = 0;
      for (int i = 0; i < splitList.size(); i++) {
        InputSplitShim split = splitList.get((startIndex + i) % splitList.size());
        retLists.add(split);
        long splitgLength = split.getLength();
        if (size + splitgLength >= targetSize) {
          LOG.info("Sample alias " + entry.getValue() + " using " + (i + 1) + "splits");
          if (size + splitgLength > targetSize) {
            split.shrinkSplit(targetSize - size);
          }
          break;
        }
        size += splitgLength;
      }

    }

    InputSplitShim[] retArray = new InputSplitShim[retLists.size()];
    return retLists.toArray(retArray);
  }

  /**
   * Create a generic Hive RecordReader than can iterate over all chunks in a
   * CombinedFileSplit.
   */
  @Override
  public RecordReader getRecordReader(InputSplit split, JobConf job,
      Reporter reporter) throws IOException {
    if (!(split instanceof CombineHiveInputSplit)) {
      return super.getRecordReader(split, job, reporter);
    }

    CombineHiveInputSplit hsplit = (CombineHiveInputSplit) split;

    String inputFormatClassName = null;
    Class inputFormatClass = null;
    try {
      inputFormatClassName = hsplit.inputFormatClassName();
      inputFormatClass = job.getClassByName(inputFormatClassName);
    } catch (Exception e) {
      throw new IOException("cannot find class " + inputFormatClassName);
    }

    pushProjectionsAndFilters(job, inputFormatClass,
        hsplit.getPath(0).toString(),
        hsplit.getPath(0).toUri().getPath());

    return ShimLoader.getHadoopShims().getCombineFileInputFormat()
        .getRecordReader(job,
        ((CombineHiveInputSplit) split).getInputSplitShim(), reporter,
        CombineHiveRecordReader.class);
  }

  static class CombineFilter implements PathFilter {
    private final List<String> pStrings = new ArrayList<String>();

    // store a path prefix in this TestFilter
    // PRECONDITION: p should always be a directory
    public CombineFilter(Path p) {
      // we need to keep the path part only because the Hadoop CombineFileInputFormat will
      // pass the path part only to accept().
      // Trailing the path with a separator to prevent partial matching.
      addPath(p);
    }

    public void addPath(Path p) {
      String pString = p.toUri().getPath().toString() + File.separator;;
      pStrings.add(pString);
    }

    // returns true if the specified path matches the prefix stored
    // in this TestFilter.
    public boolean accept(Path path) {
      for (String pString : pStrings) {
        if (path.toString().indexOf(pString) == 0) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String toString() {
      StringBuilder s = new StringBuilder();
      s.append("PathFilter: ");
      for (String pString : pStrings) {
        s.append(pString + " ");
      }
      return s.toString();
    }
  }
}
