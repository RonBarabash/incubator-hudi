/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie.utilities.deltastreamer;

import static com.uber.hoodie.common.table.HoodieTimeline.COMPACTION_ACTION;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.uber.hoodie.HoodieWriteClient;
import com.uber.hoodie.OverwriteWithLatestAvroPayload;
import com.uber.hoodie.SimpleKeyGenerator;
import com.uber.hoodie.common.model.HoodieTableType;
import com.uber.hoodie.common.table.HoodieTableMetaClient;
import com.uber.hoodie.common.table.timeline.HoodieInstant;
import com.uber.hoodie.common.table.timeline.HoodieInstant.State;
import com.uber.hoodie.common.util.CompactionUtils;
import com.uber.hoodie.common.util.FSUtils;
import com.uber.hoodie.common.util.TypedProperties;
import com.uber.hoodie.common.util.collection.Pair;
import com.uber.hoodie.exception.HoodieException;
import com.uber.hoodie.exception.HoodieIOException;
import com.uber.hoodie.utilities.HiveIncrementalPuller;
import com.uber.hoodie.utilities.UtilHelpers;
import com.uber.hoodie.utilities.schema.SchemaProvider;
import com.uber.hoodie.utilities.sources.JsonDFSSource;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;


/**
 * An Utility which can incrementally take the output from {@link HiveIncrementalPuller} and apply it to the target
 * dataset. Does not maintain any state, queries at runtime to see how far behind the target dataset is from the source
 * dataset. This can be overriden to force sync from a timestamp.
 *
 * In continuous mode, DeltaStreamer runs in loop-mode going through the below operations
 *    (a) pull-from-source
 *    (b) write-to-sink
 *    (c) Schedule Compactions if needed
 *    (d) Conditionally Sync to Hive
 *  each cycle. For MOR table with continuous mode enabled, a seperate compactor thread is allocated to execute
 *  compactions
 */
public class HoodieDeltaStreamer implements Serializable {

  private static volatile Logger log = LogManager.getLogger(HoodieDeltaStreamer.class);

  public static String CHECKPOINT_KEY = "deltastreamer.checkpoint.key";

  private final transient Config cfg;

  private transient DeltaSyncService deltaSyncService;

  public HoodieDeltaStreamer(Config cfg, JavaSparkContext jssc) throws IOException {
    this(cfg, jssc, FSUtils.getFs(cfg.targetBasePath, jssc.hadoopConfiguration()),
        getDefaultHiveConf(jssc.hadoopConfiguration()));
  }

  public HoodieDeltaStreamer(Config cfg, JavaSparkContext jssc, FileSystem fs, HiveConf hiveConf) throws IOException {
    this.cfg = cfg;
    this.deltaSyncService = new DeltaSyncService(cfg, jssc, fs, hiveConf);
  }

  public void shutdownGracefully() {
    deltaSyncService.shutdown(false);
  }

  private static HiveConf getDefaultHiveConf(Configuration cfg) {
    HiveConf hiveConf = new HiveConf();
    hiveConf.addResource(cfg);
    return hiveConf;
  }

  /**
   * Main method to start syncing
   * @throws Exception
   */
  public void sync() throws Exception {
    if (cfg.continuousMode) {
      deltaSyncService.start(this::onDeltaSyncShutdown);
      deltaSyncService.waitForShutdown();
      log.info("Delta Sync shutting down");
    } else {
      log.info("Delta Streamer running only single round");
      deltaSyncService.getDeltaSync().syncOnce();
      deltaSyncService.close();
      log.info("Shut down deltastreamer");
    }
  }

  private boolean onDeltaSyncShutdown(boolean error) {
    log.info("DeltaSync shutdown. Closing write client. Error?" + error);
    deltaSyncService.close();
    return true;
  }

  public enum Operation {
    UPSERT, INSERT, BULK_INSERT
  }

  private static class OperationConvertor implements IStringConverter<Operation> {

    @Override
    public Operation convert(String value) throws ParameterException {
      return Operation.valueOf(value);
    }
  }

  public static class Config implements Serializable {

    @Parameter(names = {"--target-base-path"}, description = "base path for the target hoodie dataset. "
        + "(Will be created if did not exist first time around. If exists, expected to be a hoodie dataset)",
        required = true)
    public String targetBasePath;

    // TODO: How to obtain hive configs to register?
    @Parameter(names = {"--target-table"}, description = "name of the target table in Hive", required = true)
    public String targetTableName;

    @Parameter(names = {"--storage-type"}, description = "Type of Storage. "
        + "COPY_ON_WRITE (or) MERGE_ON_READ", required = true)
    public String storageType;

    @Parameter(names = {"--props"}, description = "path to properties file on localfs or dfs, with configurations for "
        + "hoodie client, schema provider, key generator and data source. For hoodie client props, sane defaults are "
        + "used, but recommend use to provide basic things like metrics endpoints, hive configs etc. For sources, refer"
        + "to individual classes, for supported properties.")
    public String propsFilePath =
        "file://" + System.getProperty("user.dir") + "/src/test/resources/delta-streamer-config/dfs-source.properties";

    @Parameter(names = {"--hoodie-conf"}, description = "Any configuration that can be set in the properties file "
        + "(using the CLI parameter \"--propsFilePath\") can also be passed command line using this parameter")
    public List<String> configs = new ArrayList<>();

    @Parameter(names = {"--source-class"}, description = "Subclass of com.uber.hoodie.utilities.sources to read data. "
        + "Built-in options: com.uber.hoodie.utilities.sources.{JsonDFSSource (default), AvroDFSSource, "
        + "JsonKafkaSource, AvroKafkaSource, HiveIncrPullSource}")
    public String sourceClassName = JsonDFSSource.class.getName();

    @Parameter(names = {"--source-ordering-field"}, description = "Field within source record to decide how"
        + " to break ties between records with same key in input data. Default: 'ts' holding unix timestamp of record")
    public String sourceOrderingField = "ts";

    @Parameter(names = {"--key-generator-class"}, description = "Subclass of com.uber.hoodie.KeyGenerator "
        + "to generate a HoodieKey from the given avro record. Built in: SimpleKeyGenerator (uses "
        + "provided field names as recordkey & partitionpath. Nested fields specified via dot notation, e.g: a.b.c)")
    public String keyGeneratorClass = SimpleKeyGenerator.class.getName();

    @Parameter(names = {"--payload-class"}, description = "subclass of HoodieRecordPayload, that works off "
        + "a GenericRecord. Implement your own, if you want to do something other than overwriting existing value")
    public String payloadClassName = OverwriteWithLatestAvroPayload.class.getName();

    @Parameter(names = {"--schemaprovider-class"}, description = "subclass of com.uber.hoodie.utilities.schema"
        + ".SchemaProvider to attach schemas to input & target table data, built in options: "
        + "com.uber.hoodie.utilities.schema.FilebasedSchemaProvider."
        + "Source (See com.uber.hoodie.utilities.sources.Source) implementation can implement their own SchemaProvider."
        + " For Sources that return Dataset<Row>, the schema is obtained implicitly. "
        + "However, this CLI option allows overriding the schemaprovider returned by Source.")
    public String schemaProviderClassName = null;

    @Parameter(names = {"--transformer-class"},
        description = "subclass of com.uber.hoodie.utilities.transform.Transformer"
            + ". Allows transforming raw source dataset to a target dataset (conforming to target schema) before "
            + "writing. Default : Not set. E:g - com.uber.hoodie.utilities.transform.SqlQueryBasedTransformer (which "
            + "allows a SQL query templated to be passed as a transformation function)")
    public String transformerClassName = null;

    @Parameter(names = {"--source-limit"}, description = "Maximum amount of data to read from source. "
        + "Default: No limit For e.g: DFS-Source => max bytes to read, Kafka-Source => max events to read")
    public long sourceLimit = Long.MAX_VALUE;

    @Parameter(names = {"--op"}, description = "Takes one of these values : UPSERT (default), INSERT (use when input "
        + "is purely new data/inserts to gain speed)",
        converter = OperationConvertor.class)
    public Operation operation = Operation.UPSERT;

    @Parameter(names = {"--filter-dupes"}, description = "Should duplicate records from source be dropped/filtered out"
        + "before insert/bulk-insert")
    public Boolean filterDupes = false;

    @Parameter(names = {"--enable-hive-sync"}, description = "Enable syncing to hive")
    public Boolean enableHiveSync = false;

    @Parameter(names = {"--max-pending-compactions"},
        description = "Maximum number of outstanding inflight/requested compactions. Delta Sync will not happen unless"
            + "outstanding compactions is less than this number")
    public Integer maxPendingCompactions = 5;

    @Parameter(names = {"--continuous"}, description = "Delta Streamer runs in continuous mode running"
        + " source-fetch -> Transform -> Hudi Write in loop")
    public Boolean continuousMode = false;

    @Parameter(names = {"--spark-master"}, description = "spark master to use.")
    public String sparkMaster = "local[2]";

    @Parameter(names = {"--commit-on-errors"}, description = "Commit even when some records failed to be written")
    public Boolean commitOnErrors = false;

    @Parameter(names = {"--delta-sync-scheduling-weight"}, description =
        "Scheduling weight for delta sync as defined in "
            + "https://spark.apache.org/docs/latest/job-scheduling.html")
    public Integer deltaSyncSchedulingWeight = 1;

    @Parameter(names = {"--compact-scheduling-weight"}, description = "Scheduling weight for compaction as defined in "
        + "https://spark.apache.org/docs/latest/job-scheduling.html")
    public Integer compactSchedulingWeight = 1;

    @Parameter(names = {"--delta-sync-scheduling-minshare"}, description = "Minshare for delta sync as defined in "
        + "https://spark.apache.org/docs/latest/job-scheduling.html")
    public Integer deltaSyncSchedulingMinShare = 0;

    @Parameter(names = {"--compact-scheduling-minshare"}, description = "Minshare for compaction as defined in "
        + "https://spark.apache.org/docs/latest/job-scheduling.html")
    public Integer compactSchedulingMinShare = 0;

    @Parameter(names = {"--help", "-h"}, help = true)
    public Boolean help = false;
  }

  /**
   * Helper to set Spark Scheduling Configs dynamically
   *
   * @param cfg Config
   */
  public static Map<String, String> getSparkSchedulingConfigs(Config cfg) throws Exception {
    Map<String, String> additionalSparkConfigs = new HashMap<>();
    if (cfg.continuousMode && cfg.storageType.equals(HoodieTableType.MERGE_ON_READ.name())) {
      String sparkSchedulingConfFile = SchedulerConfGenerator.generateAndStoreConfig(cfg.deltaSyncSchedulingWeight,
          cfg.compactSchedulingWeight, cfg.deltaSyncSchedulingMinShare, cfg.compactSchedulingMinShare);
      additionalSparkConfigs.put("spark.scheduler.allocation.file", sparkSchedulingConfFile);
    }
    return additionalSparkConfigs;
  }

  public static void main(String[] args) throws Exception {
    final Config cfg = new Config();
    JCommander cmd = new JCommander(cfg, args);
    if (cfg.help || args.length == 0) {
      cmd.usage();
      System.exit(1);
    }

    Map<String, String> additionalSparkConfigs = getSparkSchedulingConfigs(cfg);
    JavaSparkContext jssc = UtilHelpers.buildSparkContext("delta-streamer-" + cfg.targetTableName,
        cfg.sparkMaster, additionalSparkConfigs);
    if (!("FAIR".equals(jssc.getConf().get("spark.scheduler.mode")))
        && cfg.continuousMode && cfg.storageType.equals(HoodieTableType.MERGE_ON_READ.name())) {
      log.warn("Job Scheduling Configs will not be in effect as spark.scheduler.mode "
          + "is not set to FAIR at instatiation time. Continuing without scheduling configs");
    }
    new HoodieDeltaStreamer(cfg, jssc).sync();
  }


  /**
   * Syncs data either in single-run or in continuous mode.
   */
  public static class DeltaSyncService extends AbstractDeltaStreamerService {

    /**
     * Delta Sync Config
     */
    private final HoodieDeltaStreamer.Config cfg;

    /**
     * Schema provider that supplies the command for reading the input and writing out the target table.
     */
    private transient SchemaProvider schemaProvider;

    /**
     * Spark Session
     */
    private transient SparkSession sparkSession;

    /**
     * Spark context
     */
    private transient JavaSparkContext jssc;

    /**
     * Bag of properties with source, hoodie client, key generator etc.
     */
    TypedProperties props;

    /**
     * Async Compactor Service
     */
    private AsyncCompactService asyncCompactService;

    /**
     * Table Type
     */
    private final HoodieTableType tableType;

    /**
     * Delta Sync
     */
    private transient DeltaSync deltaSync;

    public DeltaSyncService(HoodieDeltaStreamer.Config cfg, JavaSparkContext jssc, FileSystem fs, HiveConf hiveConf)
        throws IOException {
      this.cfg = cfg;
      this.jssc = jssc;
      this.sparkSession = SparkSession.builder().config(jssc.getConf()).getOrCreate();

      if (fs.exists(new Path(cfg.targetBasePath))) {
        HoodieTableMetaClient meta = new HoodieTableMetaClient(
            new Configuration(fs.getConf()), cfg.targetBasePath, false);
        tableType = meta.getTableType();
      } else {
        tableType = HoodieTableType.valueOf(cfg.storageType);
      }

      this.props = UtilHelpers.readConfig(fs, new Path(cfg.propsFilePath), cfg.configs).getConfig();
      log.info("Creating delta streamer with configs : " + props.toString());
      this.schemaProvider = UtilHelpers.createSchemaProvider(cfg.schemaProviderClassName, props, jssc);

      if (cfg.filterDupes) {
        cfg.operation = cfg.operation == Operation.UPSERT ? Operation.INSERT : cfg.operation;
      }

      deltaSync = new DeltaSync(cfg, sparkSession, schemaProvider, tableType,
          props, jssc, fs, hiveConf, this::onInitializingWriteClient);
    }

    public DeltaSync getDeltaSync() {
      return deltaSync;
    }

    @Override
    protected Pair<CompletableFuture, ExecutorService> startService() {
      ExecutorService executor = Executors.newFixedThreadPool(1);
      return Pair.of(CompletableFuture.supplyAsync(() -> {
        boolean error = false;
        if (cfg.continuousMode && tableType.equals(HoodieTableType.MERGE_ON_READ)) {
          // set Scheduler Pool.
          log.info("Setting Spark Pool name for delta-sync to " + SchedulerConfGenerator.DELTASYNC_POOL_NAME);
          jssc.setLocalProperty("spark.scheduler.pool", SchedulerConfGenerator.DELTASYNC_POOL_NAME);
        }
        try {
          while (!isShutdownRequested()) {
            try {
              Optional<String> scheduledCompactionInstant = deltaSync.syncOnce();
              if (scheduledCompactionInstant.isPresent()) {
                log.info("Enqueuing new pending compaction instant (" + scheduledCompactionInstant + ")");
                asyncCompactService.enqueuePendingCompaction(new HoodieInstant(State.REQUESTED, COMPACTION_ACTION,
                    scheduledCompactionInstant.get()));
                asyncCompactService.waitTillPendingCompactionsReducesTo(cfg.maxPendingCompactions);
              }
            } catch (Exception e) {
              log.error("Shutting down delta-sync due to exception", e);
              error = true;
              throw new HoodieException(e.getMessage(), e);
            }
          }
        } finally {
          shutdownCompactor(error);
        }
        return true;
      }, executor), executor);
    }

    /**
     * Shutdown compactor as DeltaSync is shutdown
     */
    private void shutdownCompactor(boolean error) {
      log.info("Delta Sync shutdown. Error ?" + error);
      if (asyncCompactService != null) {
        log.warn("Gracefully shutting down compactor");
        asyncCompactService.shutdown(false);
      }
    }

    /**
     * Callback to initialize write client and start compaction service if required
     * @param writeClient HoodieWriteClient
     * @return
     */
    protected Boolean onInitializingWriteClient(HoodieWriteClient writeClient) {
      if (tableType.equals(HoodieTableType.MERGE_ON_READ)) {
        asyncCompactService = new AsyncCompactService(jssc, writeClient);
        // Enqueue existing pending compactions first
        HoodieTableMetaClient meta = new HoodieTableMetaClient(
            new Configuration(jssc.hadoopConfiguration()), cfg.targetBasePath, true);
        List<HoodieInstant> pending = CompactionUtils.getPendingCompactionInstantTimes(meta);
        pending.stream().forEach(hoodieInstant -> asyncCompactService.enqueuePendingCompaction(hoodieInstant));
        asyncCompactService.start((error) -> {
          // Shutdown DeltaSync
          shutdown(false);
          return true;
        });
        try {
          asyncCompactService.waitTillPendingCompactionsReducesTo(cfg.maxPendingCompactions);
        } catch (InterruptedException ie) {
          throw new HoodieException(ie);
        }
      }
      return true;
    }

    /**
     * Close all resources
     */
    public void close() {
      if (null != deltaSync) {
        deltaSync.close();
      }
    }

    public SchemaProvider getSchemaProvider() {
      return schemaProvider;
    }

    public SparkSession getSparkSession() {
      return sparkSession;
    }

    public JavaSparkContext getJavaSparkContext() {
      return jssc;
    }

    public AsyncCompactService getAsyncCompactService() {
      return asyncCompactService;
    }

    public TypedProperties getProps() {
      return props;
    }
  }

  /**
   * Async Compactor Service tha runs in separate thread. Currently, only one compactor is allowed to run at any time.
   */
  public static class AsyncCompactService extends AbstractDeltaStreamerService {

    private final int maxConcurrentCompaction;
    private transient Compactor compactor;
    private transient JavaSparkContext jssc;
    private transient BlockingQueue<HoodieInstant> pendingCompactions = new LinkedBlockingQueue<>();
    private transient ReentrantLock queueLock = new ReentrantLock();
    private transient Condition consumed = queueLock.newCondition();

    public AsyncCompactService(JavaSparkContext jssc, HoodieWriteClient client) {
      this.jssc = jssc;
      this.compactor = new Compactor(client, jssc);
      //TODO: HUDI-157 : Only allow 1 compactor to run in parallel till Incremental View on MOR is fully implemented.
      this.maxConcurrentCompaction = 1;
    }

    /**
     * Enqueues new Pending compaction
     */
    public void enqueuePendingCompaction(HoodieInstant instant) {
      pendingCompactions.add(instant);
    }

    /**
     * Wait till outstanding pending compactions reduces to the passed in value
     * @param numPendingCompactions Maximum pending compactions allowed
     * @throws InterruptedException
     */
    public void waitTillPendingCompactionsReducesTo(int numPendingCompactions) throws InterruptedException {
      try {
        queueLock.lock();
        while (!isShutdown() && (pendingCompactions.size() > numPendingCompactions)) {
          consumed.await();
        }
      } finally {
        queueLock.unlock();
      }
    }

    /**
     * Fetch Next pending compaction if available
     * @return
     * @throws InterruptedException
     */
    private HoodieInstant fetchNextCompactionInstant() throws InterruptedException {
      log.info("Compactor waiting for next instant for compaction upto 60 seconds");
      HoodieInstant instant = pendingCompactions.poll(60, TimeUnit.SECONDS);
      if (instant != null) {
        try {
          queueLock.lock();
          // Signal waiting thread
          consumed.signal();
        } finally {
          queueLock.unlock();
        }
      }
      return instant;
    }

    /**
     * Start Compaction Service
     */
    protected Pair<CompletableFuture, ExecutorService> startService() {
      ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentCompaction);
      List<CompletableFuture<Boolean>> compactionFutures =
          IntStream.range(0, maxConcurrentCompaction).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
              // Set Compactor Pool Name for allowing users to prioritize compaction
              log.info("Setting Spark Pool name for compaction to " + SchedulerConfGenerator.COMPACT_POOL_NAME);
              jssc.setLocalProperty("spark.scheduler.pool", SchedulerConfGenerator.COMPACT_POOL_NAME);

              while (!isShutdownRequested()) {
                final HoodieInstant instant = fetchNextCompactionInstant();
                if (null != instant) {
                  compactor.compact(instant);
                }
              }
              log.info("Compactor shutting down properly!!");
            } catch (InterruptedException ie) {
              log.warn("Compactor executor thread got interrupted exception. Stopping", ie);
            } catch (IOException e) {
              log.error("Compactor executor failed", e);
              throw new HoodieIOException(e.getMessage(), e);
            }
            return true;
          }, executor)).collect(Collectors.toList());
      return Pair.of(CompletableFuture.allOf(compactionFutures.stream().toArray(CompletableFuture[]::new)), executor);
    }
  }

  public DeltaSyncService getDeltaSyncService() {
    return deltaSyncService;
  }
}
