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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.AccessLogData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.rpc.Constants.ACCESS_LOG_KEY;

/**
 * Record access log for the service.
 * <p>
 * Logger key is <code><b>dubbo.accesslog</b></code>.
 * In order to configure access log appear in the specified appender only, additivity need to be configured in log4j's
 * config file, for example:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 *
 * AccessLogFilter 主要用于记录日志，它的主要功能是将 Provider 或者 Consumer 的日志信息写入文件中。
 * AccessLogFilter 会先将日志消息放入内存日志集合中缓存，当缓存大小超过一定阈值之后，会触发日志的写入。
 * 若长时间未触发日志文件写入，则由定时任务定时写入。
 *
 * 在 AccessLogFilter 的构造方法中，会启动一个定时任务，定时调用上面介绍的 writeLogSetToFile() 方法，定时写入日志
 */
@Activate(group = PROVIDER, value = ACCESS_LOG_KEY)
public class AccessLogFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final String LOG_KEY = "dubbo.accesslog";

    private static final String LINE_SEPARATOR = "line.separator";

    private static final int LOG_MAX_BUFFER = 5000;

    private static final long LOG_OUTPUT_INTERVAL = 5000;

    private static final String FILE_DATE_FORMAT = "yyyyMMdd";

    // It's safe to declare it as singleton since it runs on single thread only
    private static final DateFormat FILE_NAME_FORMATTER = new SimpleDateFormat(FILE_DATE_FORMAT);

    private static final Map<String, Queue<AccessLogData>> LOG_ENTRIES = new ConcurrentHashMap<>();

    /**
     * 启动一个线程池
     */
    private static final ScheduledExecutorService LOG_SCHEDULED = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-Access-Log", true));

    /**
     * Default constructor initialize demon thread for writing into access log file with names with access log key
     * defined in url <b>accesslog</b>
     */
    public AccessLogFilter() {
        // 启动一个定时任务，定期执行writeLogSetToFile()方法，完成日志写入 5000ms
        LOG_SCHEDULED.scheduleWithFixedDelay(this::writeLogToFile, LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * This method logs the access log for service method invocation call.
     *
     * @param invoker service
     * @param inv     Invocation service method.
     * @return Result from service method.
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            String accessLogKey = invoker.getUrl().getParameter(ACCESS_LOG_KEY);
            if (ConfigUtils.isNotEmpty(accessLogKey)) {// 获取ACCESS_LOG_KEY
                // 构造AccessLogData对象，其中记录了日志信息，例如，调用的服务名称、方法名称、version等
                AccessLogData logData = AccessLogData.newLogData(); 
                logData.buildAccessLogData(invoker, inv);
                log(accessLogKey, logData);
            }
        } catch (Throwable t) {
            logger.warn("Exception in AccessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        // 调用下一个Invoker
        return invoker.invoke(inv);
    }

    private void log(String accessLog, AccessLogData accessLogData) {
        // 根据ACCESS_LOG_KEY获取对应的缓存集合
        Queue<AccessLogData> logQueue = LOG_ENTRIES.computeIfAbsent(accessLog, k -> new ConcurrentLinkedQueue<>());

        if (logQueue.size() < LOG_MAX_BUFFER) {// 缓存大小未超过阈值
            logQueue.add(accessLogData);
        } else {// 缓存大小超过阈值，触发缓存数据写入文件
            logger.warn("AccessLog buffer is full. Do a force writing to file to clear buffer.");
            //just write current logQueue to file.
            writeLogQueueToFile(accessLog, logQueue);
            //after force writing, add accessLogData to current logQueue
            // 完成文件写入之后，再次写入缓存
            logQueue.add(accessLogData);
        }
    }

    /**
     * 在 writeLogQueueToFile() 方法中，会按照 ACCESS_LOG_KEY 的值将日志信息写入不同的日志文件中：
     *
     * 如果 ACCESS_LOG_KEY 配置的值为 true 或 default，会使用 Dubbo 默认提供的统一日志框架，输出到日志文件中；
     *
     * 如果 ACCESS_LOG_KEY 配置的值不为 true 或 default，则 ACCESS_LOG_KEY 配置值会被当作 access log 文件的名称，
     * AccessLogFilter 会创建相应的目录和文件，并完成日志的输出。
     */
    private void writeLogQueueToFile(String accessLog, Queue<AccessLogData> logQueue) {
        try {
            if (ConfigUtils.isDefault(accessLog)) {
                // ACCESS_LOG_KEY配置值为true或是default
                processWithServiceLogger(logQueue);
            } else {// ACCESS_LOG_KEY配置既不是true也不是default的时候
                File file = new File(accessLog);
                createIfLogDirAbsent(file);// 创建目录
                if (logger.isDebugEnabled()) {
                    logger.debug("Append log to " + accessLog);
                }
                renameFile(file);// 创建日志文件，这里会以日期为后缀，滚动创建
                // 遍历logSet(logQueue)集合，将日志逐条写入文件
                processWithAccessKeyLogger(logQueue, file);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }


    private void writeLogToFile() {
        if (!LOG_ENTRIES.isEmpty()) {
            for (Map.Entry<String, Queue<AccessLogData>> entry : LOG_ENTRIES.entrySet()) {
                String accessLog = entry.getKey();
                Queue<AccessLogData> logQueue = entry.getValue();
                writeLogQueueToFile(accessLog, logQueue);
            }
        }
    }

    private void processWithAccessKeyLogger(Queue<AccessLogData> logQueue, File file) throws IOException {
        // 创建FileWriter，写入指定的日志文件
        FileWriter writer = new FileWriter(file, true);
        try  {
            while (!logQueue.isEmpty()) {
                writer.write(logQueue.poll().getLogMessage());
                writer.write(System.getProperty(LINE_SEPARATOR));
            }
        }finally {
            writer.flush();
            writer.close();
        }
    }

    private void processWithServiceLogger(Queue<AccessLogData> logQueue) {
        while (!logQueue.isEmpty()) {
            AccessLogData logData = logQueue.poll();// 遍历logQueue集合
            // 通过LoggerFactory获取Logger对象，并写入日志
            LoggerFactory.getLogger(LOG_KEY + "." + logData.getServiceName()).info(logData.getLogMessage());
        }
    }

    private void createIfLogDirAbsent(File file) {
        File dir = file.getParentFile();
        if (null != dir && !dir.exists()) {
            dir.mkdirs();
        }
    }

    private void renameFile(File file) {
        if (file.exists()) {
            String now = FILE_NAME_FORMATTER.format(new Date());
            String last = FILE_NAME_FORMATTER.format(new Date(file.lastModified()));
            if (!now.equals(last)) {
                File archive = new File(file.getAbsolutePath() + "." + last);
                file.renameTo(archive);
            }
        }
    }
}
