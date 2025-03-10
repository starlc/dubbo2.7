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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.common.utils.StringUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * Request.
 */
public class Request {

    // 用于生成请求的自增ID，当递增到Long.MAX_VALUE之后，会溢出到Long.MIN_VALUE，我们可以继续使用该负数作为消息ID
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);

    // 请求的ID
    private final long mId;

    // 请求版本号
    private String mVersion;

    // 请求的双向标识，如果该字段设置为true，则Server端在收到请求后，需要给Client返回一个响应
    private boolean mTwoWay = true;

    // 事件标识，例如心跳请求、只读请求等，都会带有这个标识
    private boolean mEvent = false;

    // 请求发送到Server之后，由Decoder将二进制数据解码成Request对象，
    // 如果解码环节遇到异常，则会设置该标识，然后交由其他ChannelHandler根据
    // 该标识做进一步处理
    private boolean mBroken = false;

    // 请求体，可以是任何Java类型的对象,也可以是null
    private Object mData;

    public Request() {
        mId = newId();
    }

    public Request(long id) {
        mId = id;
    }

    private static long newId() {
        // getAndIncrement() When it grows to MAX_VALUE, it will grow to MIN_VALUE, and the negative can be used as ID
        return INVOKE_ID.getAndIncrement();
    }

    private static String safeToString(Object data) {
        if (data == null) {
            return null;
        }
        String dataStr;
        try {
            dataStr = data.toString();
        } catch (Throwable e) {
            dataStr = "<Fail toString of " + data.getClass() + ", cause: " +
                    StringUtils.toString(e) + ">";
        }
        return dataStr;
    }

    public long getId() {
        return mId;
    }

    public String getVersion() {
        return mVersion;
    }

    public void setVersion(String version) {
        mVersion = version;
    }

    public boolean isTwoWay() {
        return mTwoWay;
    }

    public void setTwoWay(boolean twoWay) {
        mTwoWay = twoWay;
    }

    public boolean isEvent() {
        return mEvent;
    }

    public void setEvent(String event) {
        this.mEvent = true;
        this.mData = event;
    }

    public void setEvent(boolean mEvent) {
        this.mEvent = mEvent;
    }

    public boolean isBroken() {
        return mBroken;
    }

    public void setBroken(boolean mBroken) {
        this.mBroken = mBroken;
    }

    public Object getData() {
        return mData;
    }

    public void setData(Object msg) {
        mData = msg;
    }

    public boolean isHeartbeat() {
        return mEvent && HEARTBEAT_EVENT == mData;
    }

    public void setHeartbeat(boolean isHeartbeat) {
        if (isHeartbeat) {
            setEvent(HEARTBEAT_EVENT);
        }
    }

    public Request copy() {
        Request copy = new Request(mId);
        copy.mVersion = this.mVersion;
        copy.mTwoWay = this.mTwoWay;
        copy.mEvent = this.mEvent;
        copy.mBroken = this.mBroken;
        copy.mData = this.mData;
        return copy;
    }

    public Request copyWithoutData() {
        Request copy = new Request(mId);
        copy.mVersion = this.mVersion;
        copy.mTwoWay = this.mTwoWay;
        copy.mEvent = this.mEvent;
        copy.mBroken = this.mBroken;
        return copy;
    }

    @Override
    public String toString() {
        return "Request [id=" + mId + ", version=" + mVersion + ", twoway=" + mTwoWay + ", event=" + mEvent
                + ", broken=" + mBroken + ", data=" + (mData == this ? "this" : safeToString(mData)) + "]";
    }
}
