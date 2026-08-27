package com.hjmicro.netty;

import com.hjmicro.ClientService;
import com.hjmicro.RpcQueue;
import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.MethodInvokeDefinition;
import com.hjmicro.domain.dto.RpcRequest;
import com.hjmicro.domain.dto.RpcResult;
import com.hjmicro.netty.handler.ServerHandler;
import com.hjmicro.service.impl.rpc.HeartbeatServiceImpl;
import org.reflections.Reflections;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.cglib.proxy.InvocationHandler;
import org.springframework.cglib.proxy.Proxy;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

@Configuration
public class ProxyConfiguration implements BeanFactoryPostProcessor, BeanPostProcessor {

    private static Map<String, MethodInvokeDefinition> methodInfoMap = new HashMap<>();


    //查看当前是否选了机器
    private static ThreadLocal<String> rpcRequestThreadLocal = new ThreadLocal<>();


    //invoke
    public static Object invokeMethod(RpcRequest rpcRequest) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        MethodInvokeDefinition methodInvokeDefinition = methodInfoMap.get(rpcRequest.getSign());
        if (methodInvokeDefinition != null) {
            Method method = methodInvokeDefinition.getInstance().getClass().getMethod(methodInvokeDefinition.getMethodName(), methodInvokeDefinition.getParameterTypes());
            return method.invoke(methodInvokeDefinition.getInstance(), rpcRequest.getArgs());
        }
        throw new IllegalArgumentException("Method not found for the given object and argument");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry registry) {
            Reflections reflections = new Reflections("com.hjmicro");
            Set<Class<?>> annotated = reflections.getTypesAnnotatedWith(ClientService.class);
            for (Class<?> beanClass : annotated) {
                try {
                    String beanClassName = beanClass.getName();
                    Object o = Proxy.newProxyInstance(
                            this.getClass().getClassLoader(),
                            new Class[]{beanClass},
                            (InvocationHandler) (proxy, method, args) -> {
                                //过滤obj方法
                                if (method.getName().equals("toString") || method.getName().equals("hashCode") || method.getName().equals("equals")) {
                                    return method.invoke(proxy, args);
                                }
                                RpcRequest rpcRequest = new RpcRequest();
                                String requestId = UUID.randomUUID().toString();
                                String machineTag = rpcRequestThreadLocal.get();
                                rpcRequest.setRequestId(requestId);
                                rpcRequest.setServiceName(beanClassName);
                                rpcRequest.setMethodName(method.getName());
                                rpcRequest.setArgs(args);
                                rpcRequest.setSign(beanClassName + "#" + method.getName() + "#" + Arrays.toString(method.getParameterTypes()));
                                rpcRequest.setTargetIp(HeartbeatServiceImpl.getIpAddressByMachineTag(machineTag));
                                //发送请求
                                boolean b = ServerHandler.invokeRemoteMethod(rpcRequest,null);
                                //等待返回
                                RpcResult rpcResult = RpcQueue.waitReturn(rpcRequest);
                                return rpcResult.getResult();
                            }
                    );
                    RootBeanDefinition rootBeanDefinition = new RootBeanDefinition();
                    rootBeanDefinition.setBeanClass(beanClass);
                    rootBeanDefinition.setInstanceSupplier(() -> o);
                    String uniqueBeanName = beanClassName + "Proxy";
                    registry.registerBeanDefinition(uniqueBeanName, rootBeanDefinition);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    rpcRequestThreadLocal.remove();
                }
            }
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ServiceInterface) {
            Class<?> aClass = bean.getClass();
            if (AopUtils.isAopProxy(bean)) {
                aClass = AopProxyUtils.ultimateTargetClass(bean);
            }

            HashMap<String, Class> methodInterfaceMap = new HashMap<>();
            Class<?>[] interfaces = aClass.getInterfaces();
            for (Class<?> interfaceClass : interfaces) {
                for (Method interfaceMethod : interfaceClass.getMethods()) {
                    String name = interfaceMethod.getName();
                    methodInterfaceMap.put(name, interfaceClass);
                }
            }

            Method[] methods = aClass.getDeclaredMethods();
            for (Method method : methods) {
                Class<?> interfaceClass = methodInterfaceMap.get(method.getName());
                if (method.getDeclaringClass() != Object.class && interfaceClass != null) {
                    // Make sure there is exactly one parameter
                        //组装签名
                    String sign = interfaceClass.getName() + "#" + method.getName() + "#" + Arrays.toString(method.getParameterTypes());
                    // For each parameter type, we add the method to the corresponding list in the map
                    MethodInvokeDefinition methodInvokeDefinition = new MethodInvokeDefinition();
                    methodInvokeDefinition.setInstance(bean);
                    methodInvokeDefinition.setMethodName(method.getName());
                    methodInvokeDefinition.setReturnType(method.getReturnType());
                    methodInvokeDefinition.setParameterTypes(method.getParameterTypes());
                    methodInfoMap.put(sign, methodInvokeDefinition);
                }
            }
        }
        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }

    public static void setRemoteMachineTag(String ip){
        rpcRequestThreadLocal.set(ip);
    }

    public static void clearRemoteMachineTag(){
        rpcRequestThreadLocal.remove();
    }

}