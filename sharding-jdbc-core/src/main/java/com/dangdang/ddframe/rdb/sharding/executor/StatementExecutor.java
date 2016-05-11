/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.executor;

import com.codahale.metrics.Timer.Context;
import com.dangdang.ddframe.rdb.sharding.executor.event.DMLExecutionEvent;
import com.dangdang.ddframe.rdb.sharding.executor.event.DMLExecutionEventBus;
import com.dangdang.ddframe.rdb.sharding.executor.event.EventExecutionType;
import com.dangdang.ddframe.rdb.sharding.executor.wrapper.StatementExecutorWrapper;
import com.dangdang.ddframe.rdb.sharding.metrics.MetricsContext;
import com.google.common.base.Optional;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 多线程执行静态语句对象请求的执行器.
 * 
 * @author gaohongtao
 * @author caohao
 */
@RequiredArgsConstructor
public final class StatementExecutor {
    
    private final ExecutorEngine executorEngine;
    
    private final Collection<StatementExecutorWrapper> statementExecutorWrappers = new ArrayList<>();
    
    /**
     * 添加静态语句对象至执行上下文.
     *
     * @param statementExecutorWrapper 静态语句对象的执行上下文
     */
    public void addStatement(final StatementExecutorWrapper statementExecutorWrapper) {
        statementExecutorWrappers.add(statementExecutorWrapper);
    }
    
    /**
     * 执行SQL查询.
     * 
     * @return 结果集列表
     * @throws SQLException SQL异常
     */
    public List<ResultSet> executeQuery() throws SQLException {
        Context context = MetricsContext.start("ShardingStatement-executeQuery");
        List<ResultSet> result;
        if (1 == statementExecutorWrappers.size()) {
            StatementExecutorWrapper statementExecutorWrapper = statementExecutorWrappers.iterator().next();
            result = Collections.singletonList(statementExecutorWrapper.getStatement().executeQuery(statementExecutorWrapper.getSqlExecutionUnit().getSql()));
            MetricsContext.stop(context);
            return result;
        }
        result = executorEngine.execute(statementExecutorWrappers, new ExecuteUnit<StatementExecutorWrapper, ResultSet>() {
        
            @Override
            public ResultSet execute(final StatementExecutorWrapper input) throws Exception {
                return input.getStatement().executeQuery(input.getSqlExecutionUnit().getSql());
            }
        });
        MetricsContext.stop(context);
        return result;
    }
    
    /**
     * 执行SQL更新.
     * 
     * @return 更新数量
     * @throws SQLException SQL异常
     */
    public int executeUpdate() throws SQLException {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql);
            }
        });
    }
    
    public int executeUpdate(final int autoGeneratedKeys) throws SQLException {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, autoGeneratedKeys);
            }
        });
    }
    
    public int executeUpdate(final int[] columnIndexes) throws SQLException {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnIndexes);
            }
        });
    }
    
    public int executeUpdate(final String[] columnNames) throws SQLException {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnNames);
            }
        });
    }
    
    private int executeUpdate(final Updater updater) throws SQLException {
        Context context = MetricsContext.start("ShardingStatement-executeUpdate");
        postDMLExecutionEvents();
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        int result;
        if (1 == statementExecutorWrappers.size()) {
            return executeUpdateInternal(updater, statementExecutorWrappers.iterator().next(), isExceptionThrown, dataMap, Optional.fromNullable(context));
        }
        result = executorEngine.execute(statementExecutorWrappers, new ExecuteUnit<StatementExecutorWrapper, Integer>() {
        
            @Override
            public Integer execute(final StatementExecutorWrapper input) throws Exception {
                return executeUpdateInternal(updater, input, isExceptionThrown, dataMap, Optional.<Context>absent());
            }
        }, new MergeUnit<Integer, Integer>() {
        
            @Override
            public Integer merge(final List<Integer> results) {
                if (null == results) {
                    return 0;
                }
                int result = 0;
                for (int each : results) {
                    result += each;
                }
                return result;
            }
        });
        MetricsContext.stop(context);
        return result;
    }
    
    private int executeUpdateInternal(final Updater updater, final StatementExecutorWrapper statementExecutorWrapper,
                                      final boolean isExceptionThrown, final Map<String, Object> dataMap, final Optional<Context> context) {
        int result;
        ExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        ExecutorDataMap.setDataMap(dataMap);
        try {
            result = updater.executeUpdate(statementExecutorWrapper.getStatement(), statementExecutorWrapper.getSqlExecutionUnit().getSql());
        } catch (final SQLException ex) {
            postDMLExecutionEventsAfterExecution(statementExecutorWrapper, EventExecutionType.EXECUTE_FAILURE);
            ExecutorExceptionHandler.handleException(ex);
            return 0;
        } finally {
            if (context.isPresent()) {
                MetricsContext.stop(context.get());
            }
        }
        postDMLExecutionEventsAfterExecution(statementExecutorWrapper, EventExecutionType.EXECUTE_SUCCESS);
        return result;
    }
    
    /**
     * 执行SQL请求.
     * 
     * @return true表示执行DQL语句, false表示执行的DML语句
     * @throws SQLException SQL异常
     */
    public boolean execute() throws SQLException {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql);
            }
        });
    }
    
    public boolean execute(final int autoGeneratedKeys) throws SQLException {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, autoGeneratedKeys);
            }
        });
    }
    
    public boolean execute(final int[] columnIndexes) throws SQLException {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnIndexes);
            }
        });
    }
    
    public boolean execute(final String[] columnNames) throws SQLException {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnNames);
            }
        });
    }
    
    private boolean execute(final Executor executor) throws SQLException {
        Context context = MetricsContext.start("ShardingStatement-execute");
        postDMLExecutionEvents();
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        if (1 == statementExecutorWrappers.size()) {
            return executeInternal(executor, statementExecutorWrappers.iterator().next(), isExceptionThrown, dataMap, Optional.fromNullable(context));
        }
        List<Boolean> result = executorEngine.execute(statementExecutorWrappers, new ExecuteUnit<StatementExecutorWrapper, Boolean>() {
        
            @Override
            public Boolean execute(final StatementExecutorWrapper input) throws Exception {
                return executeInternal(executor, input, isExceptionThrown, dataMap, Optional.<Context>absent());
            }
        });
        MetricsContext.stop(context);
        return null == result ? false : result.get(0);
    }
    
    private boolean executeInternal(final Executor executor, final StatementExecutorWrapper statementExecutorWrapper,
                                    final boolean isExceptionThrown, final Map<String, Object> dataMap, final Optional<Context> context) {
        boolean result;
        ExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        ExecutorDataMap.setDataMap(dataMap);
        try {
            result = executor.execute(statementExecutorWrapper.getStatement(), statementExecutorWrapper.getSqlExecutionUnit().getSql());
        } catch (final SQLException ex) {
            postDMLExecutionEventsAfterExecution(statementExecutorWrapper, EventExecutionType.EXECUTE_FAILURE);
            ExecutorExceptionHandler.handleException(ex);
            return false;
        } finally {
            if (context.isPresent()) {
                MetricsContext.stop(context.get());
            }
        }
        postDMLExecutionEventsAfterExecution(statementExecutorWrapper, EventExecutionType.EXECUTE_SUCCESS);
        return result;
    }
    
    private void postDMLExecutionEvents() {
        for (StatementExecutorWrapper each : statementExecutorWrappers) {
            if (each.getDMLExecutionEvent().isPresent()) {
                DMLExecutionEventBus.post(each.getDMLExecutionEvent().get());
            }
        }
    }
    
    private void postDMLExecutionEventsAfterExecution(final StatementExecutorWrapper statementExecutorWrapper, final EventExecutionType eventExecutionType) {
        if (statementExecutorWrapper.getDMLExecutionEvent().isPresent()) {
            DMLExecutionEvent event = statementExecutorWrapper.getDMLExecutionEvent().get();
            event.setEventExecutionType(eventExecutionType);
            DMLExecutionEventBus.post(event);
        }
    }
    
    private interface Updater {
        
        int executeUpdate(Statement statement, String sql) throws SQLException;
    }
    
    private interface Executor {
        
        boolean execute(Statement statement, String sql) throws SQLException;
    }
}
