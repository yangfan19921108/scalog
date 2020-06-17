package com.java4all.scalog.aspect;

import com.google.gson.Gson;
import com.java4all.scalog.annotation.LogInfo;
import com.java4all.scalog.utils.SourceUtil;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * LogInfo Aspect
 * @author wangzhongxiang
 * @date 2020年06月15日 10:01:24
 */
@Aspect
@Component
public class LogInfoAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogInfoAspect.class);
    private static final String LOG_TABLE = "log_info";

    private static final String INSERT_LOG_SQL =
            "insert into " + LOG_TABLE +
                    "(`id`, `company_name`, `project_name`, `module_name`, `function_name`, `class_name`, `method_name`, `method_type`, `url`, "
                    + "`request_params`, `result`, `remark`, `cost`, `ip`, `user_id`, `user_Name`, `log_type`,"
                    + " `gmt_start`, `gmt_end`,`gmt_create`, `gmt_modified`)"
            + " values "
            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())";

    private static ThreadPoolExecutor executor =
            new ThreadPoolExecutor(4,8,10,
            TimeUnit.SECONDS,new LinkedBlockingQueue<>(10000),
            new NameThreadFactory(),new CallerRunsPolicy());

    /**
     * include subclass package
     * TODO get from configure file
     */
    @Pointcut("execution(* com.java4all.scalog.controller..*.*(..))")
    public void pointCut(){}

    @Around("pointCut()")
    public Object aroundPointCut(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object proceed = joinPoint.proceed();
        long endTime = System.currentTimeMillis();

        ServletRequestAttributes attributes =
                (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<? extends MethodSignature> clazz = signature.getDeclaringType();
        Method method = signature.getMethod();

        //not a web controller class,skip
        if(!clazz.isAnnotationPresent(Controller.class)
                && !clazz.isAnnotationPresent(RestController.class)) {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} not a web controller class,skip","待添加");
            }
            return proceed;
        }
        //not a web controller method,skip
        if(!method.isAnnotationPresent(RequestMapping.class)
                && !method.isAnnotationPresent(GetMapping.class)
                && !method.isAnnotationPresent(PostMapping.class)
                && !method.isAnnotationPresent(PutMapping.class)
                && !method.isAnnotationPresent(DeleteMapping.class)
                && !method.isAnnotationPresent(PatchMapping.class)) {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}.{} not a web controller method,skip","待添加","待添加");
            }
            return proceed;
        }
        //use Gson can resolve the args contains File,FastJson is not support
        String result = new Gson().toJson(proceed);
        long cost = endTime - startTime;
//        executor.execute(()-> this.writeLog(joinPoint, cost, result, request, clazz, method));
        this.writeLog(joinPoint, cost, result, request, clazz, method);
        return proceed;
    }

    private void writeLog(ProceedingJoinPoint joinPoint, long cost, String result,
            HttpServletRequest request, Class<? extends MethodSignature> clazz, Method method) {
        String companyName = "";
        String projectName = "";
        String moduleName = "";
        String functionName = "";
        String remark = "";
        if(method.isAnnotationPresent(LogInfo.class)) {
            LogInfo logInfo = method.getAnnotation(LogInfo.class);
            companyName = logInfo.companyName();
            projectName = logInfo.projectName();
            moduleName = logInfo.moduleName();
            functionName = logInfo.functionName();
            remark = logInfo.remark();
        }

        String url = request.getRequestURL().toString();
        String methodType = request.getMethod();
        String className = clazz.toString();
        String methodName = method.getName();
        String ip = request.getRemoteAddr();
        String requestParams = new Gson().toJson(joinPoint.getArgs());

        Connection connection = null;
        try {
            connection = SourceUtil.getConnection();
            connection.setAutoCommit(true);
            PreparedStatement ps = connection.prepareStatement(INSERT_LOG_SQL);
            ps.setString(1,companyName);
            ps.setString(2,projectName);
            ps.setString(3,moduleName);
            ps.setString(4,functionName);
            ps.setString(5,className);
            ps.setString(6,methodName);
            ps.setString(7,methodType);
            ps.setString(8,url);
            ps.setString(9,requestParams);
            ps.setString(10,result);
            ps.setString(11,remark);
            ps.setLong(12,cost);
            ps.setString(13,ip);
            ps.setString(14,"userid");
            ps.setString(15,"username");
            ps.setInt(16,1);
            ps.setDate(17,new Date(11111L));
            ps.setDate(18,new Date(4444444L));
            ps.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    private static final class NameThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger index = new AtomicInteger(1);

        public NameThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = s !=null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(group, r, "Thread-LogInfo-" + index.getAndIncrement());
            thread.setDaemon(true);
            if (thread.getPriority() != Thread.NORM_PRIORITY) {
                thread.setPriority(Thread.NORM_PRIORITY);
            }
            return thread;
        }
    }
}
