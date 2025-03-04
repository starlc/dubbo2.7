/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.metadata.report.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.metadata.definition.model.FullServiceDefinition;
import org.apache.dubbo.metadata.definition.model.ServiceDefinition;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.identifier.KeyTypeEnum;
import org.apache.dubbo.metadata.report.identifier.MetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.ServiceMetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;

import com.alibaba.fastjson.JSON;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.metadata.report.support.Constants.CYCLE_REPORT_KEY;
import static org.apache.dubbo.metadata.report.support.Constants.DEFAULT_METADATA_REPORT_CYCLE_REPORT;
import static org.apache.dubbo.metadata.report.support.Constants.DEFAULT_METADATA_REPORT_RETRY_PERIOD;
import static org.apache.dubbo.metadata.report.support.Constants.DEFAULT_METADATA_REPORT_RETRY_TIMES;
import static org.apache.dubbo.metadata.report.support.Constants.RETRY_PERIOD_KEY;
import static org.apache.dubbo.metadata.report.support.Constants.RETRY_TIMES_KEY;
import static org.apache.dubbo.metadata.report.support.Constants.SYNC_REPORT_KEY;

/**
 * AbstractMetadataReport 中提供了所有 MetadataReport 的公共实现
 */
public abstract class AbstractMetadataReport implements MetadataReport {

    protected static final String DEFAULT_ROOT = "dubbo";

    private static final int ONE_DAY_IN_MILLISECONDS = 60 * 24 * 60 * 1000;
    private static final int FOUR_HOURS_IN_MILLISECONDS = 60 * 4 * 60 * 1000;
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // Local disk cache, where the special key value.registries records the list of metadata centers, and the others are the list of notified service providers
    final Properties properties = new Properties();
    // 该线程池除了用来同步本地内存缓存与文件缓存，还会用来完成异步上报的功能
    private final ExecutorService reportCacheExecutor =
            Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveMetadataReport", true));
    // 内存缓存
    final Map<MetadataIdentifier, Object> allMetadataReports = new ConcurrentHashMap<>(4);

    // 记录最近一次元数据上报的版本，单调递增
    private final AtomicLong lastCacheChanged = new AtomicLong();
    // 用来暂存上报失败的元数据，后面会有定时任务进行重试
    final Map<MetadataIdentifier, Object> failedReports = new ConcurrentHashMap<>(4);
    private URL reportURL;// 元数据中心的URL，其中包含元数据中心的地址
    boolean syncReport;// 是否同步上报元数据
    // Local disk cache file
    File file;// 本地磁盘缓存，用来缓存上报的元数据
    // 当前MetadataReport实例是否已经初始化
    private AtomicBoolean initialized = new AtomicBoolean(false);
    // 用于重试的定时任务
    public MetadataReportRetry metadataReportRetry;

    /**
     * 在 AbstractMetadataReport 的构造方法中，首先会初始化本地的文件缓存，
     * 然后创建 MetadataReportRetry 重试任务，并启动一个周期性刷新的定时任务
     *
     * 在 AbstractMetadataReport 的构造方法中，会根据 reportServerURL（也就是后面的 metadataReportURL）
     * 参数启动一个“天”级别的定时任务，该定时任务会执行 publishAll() 方法，
     * 其中会通过 doHandleMetadataCollection() 方法将 allMetadataReports 集合中的全部元数据重新进行上报。
     * 该定时任务默认是在凌晨 02:00~06:00 启动，每天执行一次。
     * @param reportServerURL
     */
    public AbstractMetadataReport(URL reportServerURL) {

        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        setUrl(reportServerURL);
        // Start file save timer
        // 默认的本地文件缓存
        String defaultFilename =
                System.getProperty("user.home") + "/.dubbo/dubbo-metadata-" + reportServerURL.getParameter(APPLICATION_KEY) + "-" +
                        reportServerURL.getAddress().replaceAll(":", "-") + ".cache";
        String filename = reportServerURL.getParameter(FILE_KEY, defaultFilename);
        File file = null;
        if (ConfigUtils.isNotEmpty(filename)) {
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException(
                            "Invalid service store file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file;
        // 将file文件中的内容加载到properties字段中
        loadProperties();
        // 是否同步上报元数据
        syncReport = reportServerURL.getParameter(SYNC_REPORT_KEY, false);
        // 创建重试任务
        metadataReportRetry = new MetadataReportRetry(reportServerURL.getParameter(RETRY_TIMES_KEY, DEFAULT_METADATA_REPORT_RETRY_TIMES),
                reportServerURL.getParameter(RETRY_PERIOD_KEY, DEFAULT_METADATA_REPORT_RETRY_PERIOD));
        // cycle report the data switch
        // 是否周期性地上报元数据
        if (reportServerURL.getParameter(CYCLE_REPORT_KEY, DEFAULT_METADATA_REPORT_CYCLE_REPORT)) {
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboMetadataReportTimer", true));
            // 默认每隔1天将本地元数据全部刷新到元数据中心
            scheduler.scheduleAtFixedRate(this::publishAll, calculateStartTime(), ONE_DAY_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
        }
    }

    public URL getUrl() {
        return reportURL;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("metadataReport url == null");
        }
        this.reportURL = url;
    }

    /**
     * 实现了刷新本地缓存文件的全部操作
     * @param version
     */
    private void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {// 对比当前版本号和此次SaveProperties任务的版本号
            return;
        }
        if (file == null) {// 检测本地缓存文件是否存在
            return;
        }
        // Save
        try {
            // 创建lock文件
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
                 FileChannel channel = raf.getChannel()) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Can not lock the metadataReport cache file " + file.getAbsolutePath() +
                            ", ignore and retry later, maybe multi java process use the file, please config: dubbo.metadata.file=xxx.properties");
                }
                // Save
                try {
                    if (!file.exists()) {// 保证本地缓存文件存在
                        file.createNewFile();
                    }
                    // 将properties中的元数据保存到本地缓存文件中
                    try (FileOutputStream outputFile = new FileOutputStream(file)) {
                        properties.store(outputFile, "Dubbo metadataReport Cache");
                    }
                } finally {
                    lock.release(); // 释放lock文件上的锁
                }
            }
        } catch (Throwable e) {
            if (version < lastCacheChanged.get()) {// 比较版本号
                return;
            } else {
                // 如果写文件失败，则重新提交SaveProperties任务，再次尝试
                reportCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save service store file, cause: " + e.getMessage(), e);
        }
    }

    void loadProperties() {
        if (file != null && file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load service store file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load service store file " + file, e);
            }
        }
    }

    /**
     * saveProperties() 方法中会更新 properties 字段，递增本地缓存文件的版本号，
     * 最后（同步/异步）执行 SaveProperties 任务，更新本地缓存文件的内容
     * @param metadataIdentifier
     * @param value
     * @param add
     * @param sync
     */
    private void saveProperties(MetadataIdentifier metadataIdentifier, String value, boolean add, boolean sync) {
        if (file == null) {
            return;
        }

        try {
            if (add) {// 更新properties中的元数据
                properties.setProperty(metadataIdentifier.getUniqueKey(KeyTypeEnum.UNIQUE_KEY), value);
            } else {
                properties.remove(metadataIdentifier.getUniqueKey(KeyTypeEnum.UNIQUE_KEY));
            }
            // 递增版本
            long version = lastCacheChanged.incrementAndGet();
            if (sync) { // 同步更新本地缓存文件
                new SaveProperties(version).run();
            } else {// 异步更新本地缓存文件
                reportCacheExecutor.execute(new SaveProperties(version));
            }

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

    /**
     * 首先会根据 syncReport 字段值决定是同步上报还是异步上报：
     * 如果是同步上报，则在当前线程执行上报操作；如果是异步上报，则在 reportCacheExecutor 线程池中执行上报操作。
     * 具体的上报操作是在storeProviderMetadataTask() 方法中完成的
     * @param providerMetadataIdentifier
     * @param serviceDefinition
     */
    @Override
    public void storeProviderMetadata(MetadataIdentifier providerMetadataIdentifier, ServiceDefinition serviceDefinition) {
        if (syncReport) {
            storeProviderMetadataTask(providerMetadataIdentifier, serviceDefinition);
        } else {
            reportCacheExecutor.execute(() -> storeProviderMetadataTask(providerMetadataIdentifier, serviceDefinition));
        }
    }

    private void storeProviderMetadataTask(MetadataIdentifier providerMetadataIdentifier, ServiceDefinition serviceDefinition) {
        try {
            if (logger.isInfoEnabled()) {
                logger.info("store provider metadata. Identifier : " + providerMetadataIdentifier + "; definition: " + serviceDefinition);
            }
            // 将元数据记录到allMetadataReports集合
            allMetadataReports.put(providerMetadataIdentifier, serviceDefinition);
            // 如果之前上报失败，则在failedReports集合中有记录，这里上报成功之后会将其删除
            failedReports.remove(providerMetadataIdentifier);
            // 将元数据序列化成JSON字符串
            String data = JSON.toJSONString(serviceDefinition);
            // 上报序列化后的元数据
            doStoreProviderMetadata(providerMetadataIdentifier, data);
            // 将序列化后的元数据保存到本地文件缓存中
            saveProperties(providerMetadataIdentifier, data, true, !syncReport);
        } catch (Exception e) {
            // retry again. If failed again, throw exception.
            // 如果上报失败，则在failedReports集合中进行记录，然后由metadataReportRetry任务中进行重试
            failedReports.put(providerMetadataIdentifier, serviceDefinition);
            metadataReportRetry.startRetryTask();
            logger.error("Failed to put provider metadata " + providerMetadataIdentifier + " in  " + serviceDefinition + ", cause: " +
                    e.getMessage(), e);
        }
    }

    @Override
    public void storeConsumerMetadata(MetadataIdentifier consumerMetadataIdentifier, Map<String, String> serviceParameterMap) {
        if (syncReport) {
            storeConsumerMetadataTask(consumerMetadataIdentifier, serviceParameterMap);
        } else {
            reportCacheExecutor.execute(() -> storeConsumerMetadataTask(consumerMetadataIdentifier, serviceParameterMap));
        }
    }

    public void storeConsumerMetadataTask(MetadataIdentifier consumerMetadataIdentifier, Map<String, String> serviceParameterMap) {
        try {
            if (logger.isInfoEnabled()) {
                logger.info("store consumer metadata. Identifier : " + consumerMetadataIdentifier + "; definition: " + serviceParameterMap);
            }
            allMetadataReports.put(consumerMetadataIdentifier, serviceParameterMap);
            failedReports.remove(consumerMetadataIdentifier);

            Gson gson = new Gson();
            String data = gson.toJson(serviceParameterMap);
            doStoreConsumerMetadata(consumerMetadataIdentifier, data);
            saveProperties(consumerMetadataIdentifier, data, true, !syncReport);
        } catch (Exception e) {
            // retry again. If failed again, throw exception.
            failedReports.put(consumerMetadataIdentifier, serviceParameterMap);
            metadataReportRetry.startRetryTask();
            logger.error("Failed to put consumer metadata " + consumerMetadataIdentifier + ";  " + serviceParameterMap + ", cause: " +
                    e.getMessage(), e);
        }
    }

    @Override
    public void saveServiceMetadata(ServiceMetadataIdentifier metadataIdentifier, URL url) {
        if (syncReport) {
            doSaveMetadata(metadataIdentifier, url);
        } else {
            reportCacheExecutor.execute(() -> doSaveMetadata(metadataIdentifier, url));
        }
    }

    @Override
    public void removeServiceMetadata(ServiceMetadataIdentifier metadataIdentifier) {
        if (syncReport) {
            doRemoveMetadata(metadataIdentifier);
        } else {
            reportCacheExecutor.execute(() -> doRemoveMetadata(metadataIdentifier));
        }
    }

    @Override
    public List<String> getExportedURLs(ServiceMetadataIdentifier metadataIdentifier) {
        // TODO, fallback to local cache
        return doGetExportedURLs(metadataIdentifier);
    }

    @Override
    public void saveSubscribedData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, Set<String> urls) {
        if (syncReport) {
            doSaveSubscriberData(subscriberMetadataIdentifier, new Gson().toJson(urls));
        } else {
            reportCacheExecutor.execute(() -> doSaveSubscriberData(subscriberMetadataIdentifier, new Gson().toJson(urls)));
        }
    }


    @Override
    public List<String> getSubscribedURLs(SubscriberMetadataIdentifier subscriberMetadataIdentifier) {
        String content = doGetSubscribedURLs(subscriberMetadataIdentifier);
        Type setType = new TypeToken<SortedSet<String>>() {
        }.getType();
        return new Gson().fromJson(content, setType);
    }

    String getProtocol(URL url) {
        String protocol = url.getParameter(SIDE_KEY);
        protocol = protocol == null ? url.getProtocol() : protocol;
        return protocol;
    }

    /**
     * @return if need to continue
     */
    public boolean retry() {
        return doHandleMetadataCollection(failedReports);
    }

    private boolean doHandleMetadataCollection(Map<MetadataIdentifier, Object> metadataMap) {
        if (metadataMap.isEmpty()) { // 没有上报失败的元数据
            return true;
        }
        // 遍历failedReports集合中失败上报的元数据，逐个调用storeProviderMetadata()方法或storeConsumerMetadata()方法重新上报
        Iterator<Map.Entry<MetadataIdentifier, Object>> iterable = metadataMap.entrySet().iterator();
        while (iterable.hasNext()) {
            Map.Entry<MetadataIdentifier, Object> item = iterable.next();
            if (PROVIDER_SIDE.equals(item.getKey().getSide())) {
                this.storeProviderMetadata(item.getKey(), (FullServiceDefinition) item.getValue());
            } else if (CONSUMER_SIDE.equals(item.getKey().getSide())) {
                this.storeConsumerMetadata(item.getKey(), (Map) item.getValue());
            }

        }
        return false;
    }

    /**
     * not private. just for unittest.
     */
    void publishAll() {
        logger.info("start to publish all metadata.");
        this.doHandleMetadataCollection(allMetadataReports);
    }

    /**
     * between 2:00 am to 6:00 am, the time is random.
     *
     * @return
     */
    long calculateStartTime() {
        Calendar calendar = Calendar.getInstance();
        long nowMill = calendar.getTimeInMillis();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long subtract = calendar.getTimeInMillis() + ONE_DAY_IN_MILLISECONDS - nowMill;
        return subtract + (FOUR_HOURS_IN_MILLISECONDS / 2) + ThreadLocalRandom.current().nextInt(FOUR_HOURS_IN_MILLISECONDS);
    }

    class MetadataReportRetry {
        protected final Logger logger = LoggerFactory.getLogger(getClass());

        // 执行重试任务的线程池
        final ScheduledExecutorService retryExecutor =
                Executors.newScheduledThreadPool(0, new NamedThreadFactory("DubboMetadataReportRetryTimer", true));
        // 重试任务关联的Future对象
        volatile ScheduledFuture retryScheduledFuture;
        // 记录重试任务的次数
        final AtomicInteger retryCounter = new AtomicInteger(0);
        // retry task schedule period
        // 重试任务的时间间隔
        long retryPeriod;
        // if no failed report, wait how many times to run retry task.
        // 无失败上报的元数据之后，重试任务会再执行600次，才会销毁
        int retryTimesIfNonFail = 600;

        // 失败重试的次数上限，默认为100次，即重试失败100次之后会放弃
        int retryLimit;

        public MetadataReportRetry(int retryTimes, int retryPeriod) {
            this.retryPeriod = retryPeriod;
            this.retryLimit = retryTimes;
        }


        /**
         * 在 startRetryTask() 方法中，MetadataReportRetry 会创建一个重试任务，
         * 并提交到 retryExecutor 线程池中等待执行（如果已存在重试任务，则不会创建新任务）。
         * 在重试任务中会调用 AbstractMetadataReport.retry() 方法完成重新上报，当然也会判断 retryLimit 等执行条件
         */
        void startRetryTask() {
            if (retryScheduledFuture == null) {
                synchronized (retryCounter) {
                    if (retryScheduledFuture == null) {
                        retryScheduledFuture = retryExecutor.scheduleWithFixedDelay(new Runnable() {
                            @Override
                            public void run() {
                                // Check and connect to the metadata
                                try {
                                    int times = retryCounter.incrementAndGet();
                                    logger.info("start to retry task for metadata report. retry times:" + times);
                                    if (retry() && times > retryTimesIfNonFail) {
                                        cancelRetryTask();
                                    }
                                    if (times > retryLimit) {
                                        cancelRetryTask();
                                    }
                                } catch (Throwable t) { // Defensive fault tolerance
                                    logger.error("Unexpected error occur at failed retry, cause: " + t.getMessage(), t);
                                }
                            }
                        }, 500, retryPeriod, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }

        void cancelRetryTask() {
            retryScheduledFuture.cancel(false);
            retryExecutor.shutdown();
        }
    }

    private void doSaveSubscriberData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, List<String> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        List<String> encodedUrlList = new ArrayList<>(urls.size());
        for (String url : urls) {
            encodedUrlList.add(URL.encode(url));
        }
        doSaveSubscriberData(subscriberMetadataIdentifier, encodedUrlList);
    }

    /**
     * 其中， doStoreProviderMetadata() 方法是一个抽象方法，对于不同的元数据中心实现有不同的实现，这个方法的具体实现在后面会展开分析。
     * @param providerMetadataIdentifier
     * @param serviceDefinitions
     */
    protected abstract void doStoreProviderMetadata(MetadataIdentifier providerMetadataIdentifier, String serviceDefinitions);

    protected abstract void doStoreConsumerMetadata(MetadataIdentifier consumerMetadataIdentifier, String serviceParameterString);

    protected abstract void doSaveMetadata(ServiceMetadataIdentifier metadataIdentifier, URL url);

    protected abstract void doRemoveMetadata(ServiceMetadataIdentifier metadataIdentifier);

    protected abstract List<String> doGetExportedURLs(ServiceMetadataIdentifier metadataIdentifier);

    protected abstract void doSaveSubscriberData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, String urlListStr);

    protected abstract String doGetSubscribedURLs(SubscriberMetadataIdentifier subscriberMetadataIdentifier);

}
