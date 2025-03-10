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
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * ExchangeCodec.
 * 它在 TelnetCodec 的基础之上，添加了处理协议头的能力。下面是 Dubbo 协议的格式，
 * 0~7 位和 8~15 位分别是 Magic High 和 Magic Low，是固定魔数值（0xdabb），我们可以通过这两个 Byte，
 * 快速判断一个数据包是否为 Dubbo 协议，这也类似 Java 字节码文件里的魔数。
 *
 * 16 位是 Req/Res 标识，用于标识当前消息是请求还是响应。
 *
 * 17 位是 2Way 标识，用于标识当前消息是单向还是双向。
 *
 * 18 位是 Event 标识，用于标识当前消息是否为事件消息。
 *
 * 19~23 位是序列化类型的标志，用于标识当前消息使用哪一种序列化算法。
 *
 * 24~31 位是 Status 状态，用于记录响应的状态，仅在 Req/Res 为 0（响应）时有用。
 *
 * 32~95 位是 Request ID，用于记录请求的唯一标识，类型为 long。
 *
 * 96~127 位是序列化后的内容长度，该值是按字节计数，int 类型。
 *
 * 128 位之后是可变的数据，被特定的序列化算法（由序列化类型标志确定）序列化后，每个部分都是一个 byte [] 或者 byte。
 * 如果是请求包（Req/Res = 1），则每个部分依次为：Dubbo version、Service name、Service version、Method name、
 * Method parameter types、Method arguments 和 Attachments。如果是响应包（Req/Res = 0），
 * 则每个部分依次为：①返回值类型（byte），标识从服务器端返回的值类型，
 * 包括返回空值（RESPONSE_NULL_VALUE 2）、正常响应值（RESPONSE_VALUE 1）
 * 和异常（RESPONSE_WITH_EXCEPTION 0）三种；②返回值，从服务端返回的响应 bytes。
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;//协议头的字节数，16 字节，即 128 位。
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;//协议头的前 16 位，分为 MAGIC_HIGH 和 MAGIC_LOW 两个字节。
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;//用于设置 Req/Res 标志位。
    protected static final byte FLAG_TWOWAY = (byte) 0x40;//用于设置 2Way 标志位。
    protected static final byte FLAG_EVENT = (byte) 0x20;//用于设置 Event 标志位。
    protected static final int SERIALIZATION_MASK = 0x1f;//用于获取序列化类型的标志位的掩码。
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        //根据需要编码的消息类型进行分类
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();//返回的是 buffer 中 可读取的字节数
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];//16字节 128位
        buffer.readBytes(header);
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        //header[0] != MAGIC_HIGH 第一个字节不等于魔数高位 header[1] != MAGIC_LOW 第二个字节不等于魔数低位
        // 检查消息头的前两个字节是否为 Dubbo 协议的魔数(0xdabb)  如果不匹配，说明可能是 TCP 粘包，需要寻找正确的消息边界
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            // 在数据中查找魔数位置 调整 buffer 的读取位置到魔数开始处 重新截取正确的消息头
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        // 长度校验 ： 检查可读数据是否小于协议头长度(16字节) 如果不够，返回需要更多输入的标志
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length. 取数据长度 ：从协议头的第12个字节开始读取4字节，转为整数 这个长度代表消息体的长度
        int len = Bytes.bytes2int(header, 12);

        // When receiving response, how to exceed the length, then directly construct a response to the client.
        // see more detail from https://github.com/apache/dubbo/issues/7021.
        //检查消息大小限制 检查消息体长度是否超过限制 如果超过，返回错误响应
        Object obj = finishRespWhenOverPayload(channel, len, header);
        if (null != obj) {
            return obj;
        }

        checkPayload(channel, len);

        //完整性校验 ：检查可读数据是否包含完整的消息(头部+消息体) 如果不够，返回需要更多输入的标志
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        //解码消息体 ：创建输入流读取消息体 调用子类实现的 decodeBody 方法进行具体解码  确保清理未使用的数据流
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            //这里调用的子类DubboCodeC的实现
            return decodeBody(channel, is, header);
        } finally {
            // 处理未读完的数据
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                if (status == Response.OK) {
                    Object data;
                    if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                            // heart beat response data is always null;
                            data = null;
                        } else {
                            data = decodeEventData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                        }
                    } else {
                        data = decodeResponseData(channel, CodecSupport.deserialize(channel.getUrl(), is, proto), getRequestData(channel, res, id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(CodecSupport.deserialize(channel.getUrl(), is, proto).readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                Object data;
                if (req.isEvent()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                        // heart beat response data is always null;
                        data = null;
                    } else {
                        data = decodeEventData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    }
                } else {
                    data = decodeRequestData(channel, CodecSupport.deserialize(channel.getUrl(), is, proto));
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(Channel channel, Response response, long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future != null) {
            Request req = future.getRequest();
            if (req != null) {
                return req.getData();
            }
        }

        logger.warn("The timeout response finally returned at "
            + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
            + ", response status is " + response.getStatus() + ", response id is " + response.getId()
            + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
            + " -> " + channel.getRemoteAddress()) + ", please check provider side for detailed result.");
        throw new IllegalArgumentException("Failed to find any request match the response, response id: " + id);
    }

    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel, req);
        // header.
        byte[] header = new byte[HEADER_LENGTH];// 该数组用来暂存协议头
        // set magic number.
        // 在header数组的前两个字节中写入魔数
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 根据当前使用的序列化设置协议头中的序列化标志位
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        if (req.isTwoWay()) {// 设置协议头中的2Way标志位
            header[2] |= FLAG_TWOWAY;
        }
        if (req.isEvent()) {// 设置协议头中的Event标志位
            header[2] |= FLAG_EVENT;
        }

        // set request id.
        // 将请求ID记录到请求头中
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 下面开始序列化请求，并统计序列化后的字节数
        // 首先使用savedWriteIndex记录ChannelBuffer当前的写入位置
        int savedWriteIndex = buffer.writerIndex();
        // 将写入位置后移16字节
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        // 根据选定的序列化方式对请求进行序列化
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);

        if (req.isHeartbeat()) {// 对心跳检测进行序列化
            // heartbeat request data is always null
            bos.write(CodecSupport.getNullBytesOf(serialization));
        } else {
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            if (req.isEvent()) {// 对事件进行序列化
                encodeEventData(channel, out, req.getData());
            } else {// 对Dubbo请求进行序列化，具体在DubboCodec中实现
                encodeRequestData(channel, out, req.getData(), req.getVersion());
            }
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
        }

        bos.flush();
        bos.close();// 完成序列化
        int len = bos.writtenBytes(); // 统计请求序列化之后，得到的字节数
        checkPayload(channel, len);// 限制一下请求的字节长度
        Bytes.int2bytes(len, header, 12);// 将字节数写入header数组中

        // write
        // 下面调整ChannelBuffer当前的写入位置，并将协议头写入Buffer中
        buffer.writerIndex(savedWriteIndex);
        buffer.writeBytes(header); // write header.
        // 最后，将ChannelBuffer的写入位置移动到正确的位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel, res);
            // header.
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }
            // set response status.
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            Bytes.long2bytes(res.getId(), header, 4);

            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);

            // encode response data or error message.
            if (status == Response.OK) {
                if(res.isHeartbeat()){
                    // heartbeat response data is always null
                    bos.write(CodecSupport.getNullBytesOf(serialization));
                }else {
                    ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
                    if (res.isEvent()) {
                        encodeEventData(channel, out, res.getResult());
                    } else {
                        encodeResponseData(channel, out, res.getResult(), res.getVersion());
                    }
                    out.flushBuffer();
                    if (out instanceof Cleanable) {
                        ((Cleanable) out).cleanup();
                    }
                }
            } else {
                ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
                out.writeUTF(res.getErrorMessage());
                out.flushBuffer();
                if (out instanceof Cleanable) {
                    ((Cleanable) out).cleanup();
                }
            }

            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            checkPayload(channel, len);
            Bytes.int2bytes(len, header, 12);
            // write
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.SERIALIZATION_ERROR);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeEvent(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in, byte[] eventBytes) throws IOException {
        try {
            if (eventBytes != null) {
                int dataLen = eventBytes.length;
                int threshold = ConfigurationUtils.getSystemConfiguration().getInt("deserialization.event.size", 15);
                if (dataLen > threshold) {
                    throw new IllegalArgumentException("Event data too long, actual size " + dataLen + ", threshold " + threshold + " rejected for security consideration.");
                }
            }
            return in.readEvent();
        } catch (IOException | ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Decode dubbo protocol event failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }

    private Object finishRespWhenOverPayload(Channel channel, long size, byte[] header) {
        int payload = getPayload(channel);
        boolean overPayload = isOverPayload(payload, size);
        if (overPayload) {
            long reqId = Bytes.bytes2long(header, 4);
            byte flag = header[2];
            if ((flag & FLAG_REQUEST) == 0) {
                Response res = new Response(reqId);
                if ((flag & FLAG_EVENT) != 0) {
                    res.setEvent(true);
                }
                res.setStatus(Response.CLIENT_ERROR);
                String errorMsg = "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel;
                logger.error(errorMsg);
                res.setErrorMessage(errorMsg);
                return res;
            }
        }
        return null;
    }
}
