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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.MAX_PROXY_COUNT;

/**
 * Proxy. 为消费端创建接口代理类
 * Consumer 端的 Proxy 底层屏蔽了复杂的网络交互、集群策略以及 Dubbo 内部的 Invoker 等概念，提供给上层使用的是业务接口
 *
 * Proxy 中的 getProxy() 方法提供了动态创建代理类的核心实现。这个创建代理类的流程比较长，
 * 为了便于你更好地理解，这里我们将其拆开，一步步进行分析。
 *
 * 首先是查找 PROXY_CACHE_MAP 这个代理类缓存（new WeakHashMap<ClassLoader, Map<String, Object>>() 类型），
 * 其中第一层 Key 是 ClassLoader 对象，第二层 Key 是上面整理得到的接口拼接而成的，
 * Value 是被缓存的代理类的 WeakReference（弱引用）。
 *
 * WeakReference（弱引用）的特性是：WeakReference 引用的对象生命周期是两次 GC 之间，
 * 也就是说当垃圾收集器扫描到只具有弱引用的对象时，无论当前内存空间是否足够，都会回收该对象。
 * （由于垃圾收集器是一个优先级很低的线程，不一定会很快发现那些只具有弱引用的对象。）
 *
 * WeakReference 的特性决定了它特别适合用于数据可恢复的内存型缓存。查找缓存的结果有下面三个：
 *
 * 如果缓存中查找不到任务信息，则会在缓存中添加一个 PENDING_GENERATION_MARKER 占位符，
 * 当前线程后续创建生成代理类并最终替换占位符。
 *
 * 如果在缓存中查找到了 PENDING_GENERATION_MARKER 占位符，说明其他线程已经在生成相应的代理类了，当前线程会阻塞等待。
 *
 * 如果缓存中查找到完整代理类，则会直接返回，不会再执行后续动态代理类的生成。
 *
 *
 * 完成缓存的查找之后，下面我们再来看代理类的生成过程。
 *
 * 第一步，调用 ClassGenerator.newInstance() 方法创建 ClassLoader 对应的 ClassPool。
 * ClassGenerator 中封装了 Javassist 的基本操作，还定义了很多字段用来暂存代理类的信息，
 * 在其 toClass() 方法中会用这些暂存的信息来动态生成代理类。下面就来简单说明一下这些字段。
 *
 * mClassName（String 类型）：代理类的类名。
 *
 * mSuperClass（String 类型）：代理类父类的名称。
 *
 * mInterfaces（Set<String> 类型）：代理类实现的接口。
 *
 * mFields（List类型）：代理类中的字段。
 *
 * mConstructors（List<String>类型）：代理类中全部构造方法的信息，其中包括构造方法的具体实现。
 *
 * mMethods（List<String>类型）：代理类中全部方法的信息，其中包括方法的具体实现。
 *
 * mDefaultConstructor（boolean 类型）：标识是否为代理类生成的默认构造方法。
 *
 * 在 ClassGenerator 的 toClass() 方法中，会根据上述字段用 Javassist 生成代理类
 *
 * 第二步，从 PROXY_CLASS_COUNTER 字段（AtomicLong类型）中获取一个 id 值，作为代理类的后缀，这主要是为了避免类名重复发生冲突。
 *
 * 第三步，遍历全部接口，获取每个接口中定义的方法，对每个方法进行如下处理：
 *
 * 加入 worked 集合（Set<String> 类型）中，用来判重。
 *
 * 将方法对应的 Method 对象添加到 methods 集合（List<Method> 类型）中。
 *
 * 获取方法的参数类型以及返回类型，构建方法体以及 return 语句。
 *
 * 将构造好的方法添加到 ClassGenerator 中的 mMethods 集合中进行缓存。
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = (proxy, method, args) -> null;
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    private static final Map<ClassLoader, Map<String, Object>> PROXY_CACHE_MAP = new WeakHashMap<ClassLoader, Map<String, Object>>();
    // cache class, avoid PermGen OOM.
    private static final Map<ClassLoader, Map<String, Object>> PROXY_CLASS_MAP = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PENDING_GENERATION_MARKER = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(Class<?>... ics) {
        return getProxy(ClassUtils.getClassLoader(Proxy.class), ics);
    }

    /**
     * Get proxy.
     *
     * @param cl  class loader.
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        if (ics.length > MAX_PROXY_COUNT) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ics.length; i++) {// 循环处理每个接口类
            String itf = ics[i].getName();
            if (!ics[i].isInterface()) {// 传入的必须是接口类，否则直接报错
                throw new RuntimeException(itf + " is not a interface.");
            }
            // 加载接口类，加载失败则直接报错
            Class<?> tmp = null;
            try {
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }

            if (tmp != ics[i]) {
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");
            }

            sb.append(itf).append(';');// 将接口类的完整名称用分号连接起来
        }

        // use interface class name list as key.
        // 接口列表将会作为第二层集合的Key
        String key = sb.toString();

        // get cache by class loader.
        final Map<String, Object> cache;
        // cache class
        final Map<String, Object> classCache;
        synchronized (PROXY_CACHE_MAP) {// 加锁同步
            cache = PROXY_CACHE_MAP.computeIfAbsent(cl, k -> new HashMap<>());
            classCache = PROXY_CLASS_MAP.computeIfAbsent(cl, k -> new HashMap<>());
        }

        Proxy proxy = null;
        synchronized (cache) {// 加锁
            do {
                Object value = cache.get(key);
                if (value instanceof Reference<?>) {// 获取到WeakReference
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null) {// 查找到缓存的代理类
                        return proxy;
                    }
                }

                // get Class by key.
                Object clazzObj = classCache.get(key);
                if (null == clazzObj || clazzObj instanceof Reference<?>) {
                    Class<?> clazz = null;
                    if (clazzObj instanceof Reference<?>) {
                        clazz = (Class<?>) ((Reference<?>) clazzObj).get();
                    }

                    if (null == clazz) {
                        if (value == PENDING_GENERATION_MARKER) {// 获取到占位符
                            try {
                                cache.wait();// 阻塞等待其他线程生成好代理类，并添加到缓存中
                            } catch (InterruptedException e) {
                            }
                        } else {// 设置占位符，由当前线程生成代理类
                            cache.put(key, PENDING_GENERATION_MARKER);
                            break;// 退出当前循环
                        }
                    } else {
                        try {
                            proxy = (Proxy) clazz.newInstance();
                            return proxy;
                        } catch (InstantiationException | IllegalAccessException e) {
                            throw new RuntimeException(e);
                        } finally {
                            if (null == proxy) {
                                cache.remove(key);
                            } else {
                                cache.put(key, new SoftReference<Proxy>(proxy));
                            }
                        }
                    }
                }
            }
            while (true);
        }

        // 后续动态生成代理类的逻辑
        //第一步，调用 ClassGenerator.newInstance() 方法创建 ClassLoader 对应的 ClassPool。
        //第二步，从 PROXY_CLASS_COUNTER 字段（AtomicLong类型）中获取一个 id 值，作为代理类的后缀，这主要是为了避免类名重复发生冲突。
        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            ccp = ClassGenerator.newInstance(cl);

            Set<String> worked = new HashSet<>();
            List<Method> methods = new ArrayList<>();

            for (int i = 0; i < ics.length; i++) {
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {// 如果接口不是public的，则需要保证所有接口在一个包下
                        pkg = npkg;
                    } else {
                        if (!pkg.equals(npkg)) {
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                        }
                    }
                }
                ccp.addInterface(ics[i]);// 向ClassGenerator中添加接口

                for (Method method : ics[i].getMethods()) {// 遍历接口中的每个方法
                    String desc = ReflectUtils.getDesc(method);
                    // 跳过已经重复方法以及static方法
                    if (worked.contains(desc) || Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    worked.add(desc);// 将方法描述添加到worked这个Set集合中，进行去重

                    int ix = methods.size();
                    Class<?> rt = method.getReturnType();// 获取方法的返回值
                    Class<?>[] pts = method.getParameterTypes();// 获取方法的参数列表
                    // 创建方法体
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    for (int j = 0; j < pts.length; j++) {
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    }
                    code.append(" Object ret = handler.invoke(this, methods[").append(ix).append("], args);");
                    if (!Void.TYPE.equals(rt)) {// 生成return语句
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    }
                    // 将生成好的方法添加到ClassGenerator中缓存
                    methods.add(method);
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            /**
             * 以 Demo 示例（即 dubbo-demo 模块中的 Demo）中的 sayHello() 方法为例，生成的方法如下所示
             * public java.lang.String sayHello(java.lang.String arg0){
             *   Object[] args = new Object[1];
             *   args[0] = ($w)$1;
             *   // 这里通过InvocationHandler.invoke()方法调用目标方法
             *   Object ret = handler.invoke(this, methods[3], args);
             *   return (java.lang.String)ret;
             * }
             */
            if (pkg == null) {
                pkg = PACKAGE_NAME;
            }

            //第四步，开始创建代理实例类（ProxyInstance）和代理类。这里我们先创建代理实例类，需要向 ClassGenerator 中添加相应的信息，
            // 例如，类名、默认构造方法、字段、父类以及一个 newInstance() 方法
            // create ProxyInstance class.
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[] {InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            ccp.addDefaultConstructor();
            Class<?> clazz = ccp.toClass();
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            // create Proxy class.
            //接下来创建代理类，它实现了 Proxy 接口，并实现了 newInstance() 方法，该方法会直接返回上面代理实例类的对象
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            Class<?> pc = ccm.toClass();
            proxy = (Proxy) pc.newInstance();
            /**
             * 生成的代理类如下所示：
             *
             * package com.apache.dubbo.common.bytecode;
             * public class Proxy0 implements Proxy {
             *     public void Proxy0() {}
             *     public Object newInstance(InvocationHandler h){
             *         return new proxy0(h);
             *     }
             * }
             */
            synchronized (classCache) {
                classCache.put(key, new SoftReference<Class<?>>(pc));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            /**
             * 第五步，也就是最后一步，在 finally 代码块中，会释放 ClassGenerator 的相关资源，
             * 将生成的代理类添加到 PROXY_CACHE_MAP 缓存中保存，
             * 同时会唤醒所有阻塞在 PROXY_CACHE_MAP 缓存上的线程，重新检测需要的代理类是否已经生成完毕。
             */
            if (ccp != null) {
                ccp.release();
            }
            if (ccm != null) {
                ccm.release();
            }
            synchronized (cache) {
                if (proxy == null) {
                    cache.remove(key);
                } else {
                    cache.put(key, new SoftReference<>(proxy));
                }
                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl) {
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            }
            if (Byte.TYPE == cl) {
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            }
            if (Character.TYPE == cl) {
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            }
            if (Double.TYPE == cl) {
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            }
            if (Float.TYPE == cl) {
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            }
            if (Integer.TYPE == cl) {
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            }
            if (Long.TYPE == cl) {
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            }
            if (Short.TYPE == cl) {
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            }
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
