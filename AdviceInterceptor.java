package com.hfy.advice;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hfy.entity.OperatorSql;
import com.hfy.service.OperatorSqlService;
import com.hfy.utils.SpringUtils;

/**
 * 
 * desc: 用于操作日志记录用户操作的sql语句
 * @author haw 2016年9月2日
 */
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
public class AdviceInterceptor implements Interceptor {
    private static final Logger logger = LoggerFactory.getLogger(AdviceInterceptor.class);

    private static final ThreadLocal<String> OPERATOR_ID = new ThreadLocal<String>();

    @Override
    public Object intercept(Invocation invocation)
        throws Throwable {
        Object rtn = invocation.proceed();
        OperatorSqlService operatorSqlService = SpringUtils.getBean("operatorSqlServiceImpl", OperatorSqlService.class);
        if (operatorSqlService != null) {
            final Object[] args = invocation.getArgs();
            MappedStatement ms = (MappedStatement) args[0];
            Object parameterObject = args[1];
            BoundSql boundSql = ms.getBoundSql(parameterObject);
            Map<String, Object> parameters = this.setPageParameter(ms, parameterObject, boundSql);
            String sql = removeBreakingWhitespace(boundSql.getSql());
            
            if(!sql.contains("operator_sql")){
                OperatorSql operatorSql = new OperatorSql();
                // 添加operator_log表的id到operator_sql表，方便做级联删除
                operatorSql.setOperatorId(OPERATOR_ID.get());
                operatorSql.setSqlStatement(sql);
                operatorSql.setParameters(parameters.toString());
                
                operatorSqlService.add(operatorSql);
                logger.debug(String.format("AdviceInterceptor ID = [%s],SQL=[%s]", OPERATOR_ID.get(), sql));
                logger.debug(String.format("AdviceInterceptor ID = [%s],Parameters=[%s]", OPERATOR_ID.get(), parameters));
            }
        }
        return rtn;
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof Executor) {
            return Plugin.wrap(target, this);
        } else {
            return target;
        }
    }

    @Override
    public void setProperties(Properties properties) {
        // 数据库方言
        // String dialect = properties.getProperty("dialect");
        // if (StringUtils.isEmpty(dialect)) {
        // dialect = "mysql";
        // }
        // SQLUTIL = new SqlUtil(dialect);
    }

    // 分页的id后缀
    private static final String SUFFIX_PAGE = "_PageHelper";

    // 第一个分页参数
    private static final String PAGEPARAMETER_FIRST = "First" + SUFFIX_PAGE;

    // 第二个分页参数
    private static final String PAGEPARAMETER_SECOND = "Second" + SUFFIX_PAGE;

    private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();

    private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();

    @SuppressWarnings("unchecked")
    public Map<String, Object> setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql) {
        Map<String, Object> paramMap = null;
        if (parameterObject == null) {
            paramMap = new HashMap<String, Object>();
        } else if (parameterObject instanceof Map) {
            paramMap = (Map<String, Object>) parameterObject;
        } else {
            paramMap = new HashMap<String, Object>();
            // 动态sql时的判断条件不会出现在ParameterMapping中，但是必须有，所以这里需要收集所有的getter属性
            // TypeHandlerRegistry可以直接处理的会作为一个直接使用的对象进行处理
            boolean hasTypeHandler = ms.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
            if (!hasTypeHandler) {
                MetaObject metaObject = forObject(parameterObject);
                for (String name : metaObject.getGetterNames()) {
                    paramMap.put(name, metaObject.getValue(name));
                }
            }
            // 下面这段方法，主要解决一个常见类型的参数时的问题
            if (boundSql.getParameterMappings() != null && boundSql.getParameterMappings().size() > 0) {
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    String name = parameterMapping.getProperty();
                    if (!name.equals(PAGEPARAMETER_FIRST) && !name.equals(PAGEPARAMETER_SECOND) && paramMap.get(name) == null) {
                        if (hasTypeHandler || parameterMapping.getJavaType().isAssignableFrom(parameterObject.getClass())) {
                            paramMap.put(name, parameterObject);
                        }
                    }
                }
            }
        }
        return paramMap;
    }

    /**
     * 反射对象，增加对低版本Mybatis的支持
     *
     * @param object
     *            反射对象
     * @return
     */
    private static MetaObject forObject(Object object) {
        return MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);
    }

    protected String removeBreakingWhitespace(String original) {
        StringTokenizer whitespaceStripper = new StringTokenizer(original);
        StringBuilder builder = new StringBuilder();
        while (whitespaceStripper.hasMoreTokens()) {
            builder.append(whitespaceStripper.nextToken());
            builder.append(" ");
        }
        return builder.toString();
    }

    public static void setOperatorId(String id) {
        OPERATOR_ID.set(id);
    }

}
