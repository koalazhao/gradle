/*
 * Copyright 2010 the original author or authors.
 *
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
 */

package org.gradle.execution;

import org.gradle.api.CircularReferenceException;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskState;
import org.gradle.listener.ListenerBroadcast;
import org.gradle.listener.ListenerManager;
import org.gradle.util.JUnit4GroovyMockery;
import org.gradle.util.TestClosure;
import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.api.Invocation;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.gradle.util.HelperUtil.toClosure;
import static org.gradle.util.WrapUtil.toList;
import static org.gradle.util.WrapUtil.toSet;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * @author Hans Dockter
 */
@RunWith(JMock.class)
public class DefaultTaskGraphExecuterTest {

    final JUnit4Mockery context = new JUnit4GroovyMockery();
    final ListenerManager listenerManager = context.mock(ListenerManager.class);
    final TaskFailureHandler failureHandler = context.mock(TaskFailureHandler.class);
    final List<Task> executedTasks = new ArrayList<Task>();
    DefaultTaskGraphExecuter taskExecuter;

    @Before
    public void setUp() {
        context.checking(new Expectations(){{
            one(listenerManager).createAnonymousBroadcaster(TaskExecutionGraphListener.class);
            will(returnValue(new ListenerBroadcast<TaskExecutionGraphListener>(TaskExecutionGraphListener.class)));
            one(listenerManager).createAnonymousBroadcaster(TaskExecutionListener.class);
            will(returnValue(new ListenerBroadcast<TaskExecutionListener>(TaskExecutionListener.class)));
        }});
        taskExecuter = new DefaultTaskGraphExecuter(listenerManager);
    }

    @Test
    public void testExecutesTasksInDependencyOrder() {
        Task a = task("a");
        Task b = task("b", a);
        Task c = task("c", b, a);
        Task d = task("d", c);

        taskExecuter.addTasks(toList(d));
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, b, c, d)));
    }

    @Test
    public void testExecutesDependenciesInNameOrder() {
        Task a = task("a");
        Task b = task("b");
        Task c = task("c");
        Task d = task("d", b, a, c);

        taskExecuter.addTasks(toList(d));
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, b, c, d)));
    }

    @Test
    public void testExecutesTasksInASingleBatchInNameOrder() {
        Task a = task("a");
        Task b = task("b");
        Task c = task("c");

        taskExecuter.addTasks(toList(b, c, a));
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, b, c)));
    }

    @Test
    public void testExecutesBatchesInOrderAdded() {
        Task a = task("a");
        Task b = task("b");
        Task c = task("c");
        Task d = task("d");

        taskExecuter.addTasks(toList(c, b));
        taskExecuter.addTasks(toList(d, a));
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(b, c, a, d)));
    }

    @Test
    public void testExecutesSharedDependenciesOfBatchesOnceOnly() {
        Task a = task("a");
        Task b = task("b");
        Task c = task("c", a, b);
        Task d = task("d");
        Task e = task("e", b, d);

        taskExecuter.addTasks(toList(c));
        taskExecuter.addTasks(toList(e));
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, b, c, d, e)));
    }

    @Test
    public void testAddTasksAddsDependencies() {
        Task a = task("a");
        Task b = task("b", a);
        Task c = task("c", b, a);
        Task d = task("d", c);
        taskExecuter.addTasks(toList(d));

        assertTrue(taskExecuter.hasTask(":a"));
        assertTrue(taskExecuter.hasTask(a));
        assertTrue(taskExecuter.hasTask(":b"));
        assertTrue(taskExecuter.hasTask(b));
        assertTrue(taskExecuter.hasTask(":c"));
        assertTrue(taskExecuter.hasTask(c));
        assertTrue(taskExecuter.hasTask(":d"));
        assertTrue(taskExecuter.hasTask(d));
        assertThat(taskExecuter.getAllTasks(), equalTo(toList(a, b, c, d)));
    }

    @Test
    public void testGetAllTasksReturnsTasksInExecutionOrder() {
        Task d = task("d");
        Task c = task("c");
        Task b = task("b", d, c);
        Task a = task("a", b);
        taskExecuter.addTasks(toList(a));

        assertThat(taskExecuter.getAllTasks(), equalTo(toList(c, d, b, a)));
    }

    @Test
    public void testCannotUseGetterMethodsWhenGraphHasNotBeenCalculated() {
        try {
            taskExecuter.hasTask(":a");
            fail();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo(
                    "Task information is not available, as this task execution graph has not been populated."));
        }

        try {
            taskExecuter.hasTask(task("a"));
            fail();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo(
                    "Task information is not available, as this task execution graph has not been populated."));
        }

        try {
            taskExecuter.getAllTasks();
            fail();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo(
                    "Task information is not available, as this task execution graph has not been populated."));
        }
    }

    @Test
    public void testDiscardsTasksAfterExecute() {
        Task a = task("a");
        Task b = task("b", a);

        taskExecuter.addTasks(toList(b));
        taskExecuter.execute(failureHandler);

        assertFalse(taskExecuter.hasTask(":a"));
        assertFalse(taskExecuter.hasTask(a));
        assertTrue(taskExecuter.getAllTasks().isEmpty());
    }

    @Test
    public void testCanExecuteMultipleTimes() {
        Task a = task("a");
        Task b = task("b", a);
        Task c = task("c");

        taskExecuter.addTasks(toList(b));
        taskExecuter.execute(failureHandler);
        assertThat(executedTasks, equalTo(toList(a, b)));

        executedTasks.clear();

        taskExecuter.addTasks(toList(c));

        assertThat(taskExecuter.getAllTasks(), equalTo(toList(c)));

        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(c)));
    }

    @Test
    public void testCannotAddTaskWithCircularReference() {
        Task a = createTask("a");
        Task b = task("b", a);
        Task c = task("c", b);
        dependsOn(a, c);

        try {
            taskExecuter.addTasks(toList(c));
            fail();
        } catch (CircularReferenceException e) {
            // Expected
        }
    }

    @Test
    public void testNotifiesGraphListenerBeforeExecute() {
        final TaskExecutionGraphListener listener = context.mock(TaskExecutionGraphListener.class);
        Task a = task("a");

        taskExecuter.addTaskExecutionGraphListener(listener);
        taskExecuter.addTasks(toList(a));

        context.checking(new Expectations() {{
            one(listener).graphPopulated(taskExecuter);
        }});

        taskExecuter.execute(failureHandler);
    }

    @Test
    public void testExecutesWhenReadyClosureBeforeExecute() {
        final TestClosure runnable = context.mock(TestClosure.class);
        Task a = task("a");

        taskExecuter.whenReady(toClosure(runnable));

        taskExecuter.addTasks(toList(a));

        context.checking(new Expectations() {{
            one(runnable).call(taskExecuter);
        }});

        taskExecuter.execute(failureHandler);
    }

    @Test
    public void testNotifiesTaskListenerAsTasksAreExecuted() {
        final TaskExecutionListener listener = context.mock(TaskExecutionListener.class);
        final Task a = task("a");
        final Task b = task("b");

        taskExecuter.addTaskExecutionListener(listener);
        taskExecuter.addTasks(toList(a, b));

        context.checking(new Expectations() {{
            one(listener).beforeExecute(a);
            one(listener).afterExecute(with(equalTo(a)), with(notNullValue(TaskState.class)));
            one(listener).beforeExecute(b);
            one(listener).afterExecute(with(equalTo(b)), with(notNullValue(TaskState.class)));
        }});

        taskExecuter.execute(failureHandler);
    }

    @Test
    public void testNotifiesTaskListenerWhenTaskFails() {
        final TaskExecutionListener listener = context.mock(TaskExecutionListener.class);
        final RuntimeException failure = new RuntimeException();
        final Task a = brokenTask("a", failure);

        taskExecuter.addTaskExecutionListener(listener);
        taskExecuter.addTasks(toList(a));

        context.checking(new Expectations() {{
            ignoring(failureHandler);
            one(listener).beforeExecute(a);
            one(listener).afterExecute(with(sameInstance(a)), with(notNullValue(TaskState.class)));
        }});

        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a)));
    }

    @Test
    public void testStopsExecutionOnFailureWhenFailureHandlerIndicatesThatExecutionShouldStop() {
        final RuntimeException failure = new RuntimeException();
        final RuntimeException wrappedFailure = new RuntimeException();
        final Task a = brokenTask("a", failure);
        final Task b = task("b");

        taskExecuter.addTasks(toList(a, b));

        context.checking(new Expectations(){{
            one(failureHandler).onTaskFailure(a);
            will(returnValue(false));
        }});

        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a)));
    }
    
    @Test
    public void testContinuesExecutionOnFailureWhenFailureHandlerIndicatesThatExecutionShouldContinue() {
        final RuntimeException failure = new RuntimeException();
        final Task a = brokenTask("a", failure);
        final Task b = task("b");

        taskExecuter.addTasks(toList(a, b));

        context.checking(new Expectations(){{
            one(failureHandler).onTaskFailure(a);
            will(returnValue(true));
        }});
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, b)));
    }
    
    @Test
    public void testDoesNotAttemptToExecuteTasksWhoseDependenciesFailedToExecute() {
        final RuntimeException failure = new RuntimeException();
        final Task a = brokenTask("a", failure);
        final Task b = task("b", a);
        final Task c = task("c");

        taskExecuter.addTasks(toList(b, c));

        context.checking(new Expectations() {{
            one(failureHandler).onTaskFailure(a);
            will(returnValue(true));
        }});
        taskExecuter.execute(failureHandler);
        assertThat(executedTasks, equalTo(toList(a, c)));
    }
    
    @Test
    public void testNotifiesBeforeTaskClosureAsTasksAreExecuted() {
        final TestClosure runnable = context.mock(TestClosure.class);
        final Task a = task("a");
        final Task b = task("b");

        taskExecuter.beforeTask(toClosure(runnable));

        taskExecuter.addTasks(toList(a, b));

        context.checking(new Expectations() {{
            one(runnable).call(a);
            one(runnable).call(b);
        }});

        taskExecuter.execute(failureHandler);
    }

    @Test
    public void testNotifiesAfterTaskClosureAsTasksAreExecuted() {
        final TestClosure runnable = context.mock(TestClosure.class);
        final Task a = task("a");
        final Task b = task("b");

        taskExecuter.afterTask(toClosure(runnable));

        taskExecuter.addTasks(toList(a, b));

        context.checking(new Expectations() {{
            one(runnable).call(a);
            one(runnable).call(b);
        }});

        taskExecuter.execute(failureHandler);
    }

    @Test
    public void doesNotExecuteFilteredTasks() {
        final Task a = task("a", task("a-dep"));
        Task b = task("b");
        Spec<Task> spec = new Spec<Task>() {
            public boolean isSatisfiedBy(Task element) {
                return element != a;
            }
        };

        taskExecuter.useFilter(spec);
        taskExecuter.addTasks(toList(a, b));
        assertThat(taskExecuter.getAllTasks(), equalTo(toList(b)));

        taskExecuter.execute(failureHandler);
        
        assertThat(executedTasks, equalTo(toList(b)));
    }

    @Test
    public void doesNotExecuteFilteredDependencies() {
        final Task a = task("a", task("a-dep"));
        Task b = task("b");
        Task c = task("c", a, b);
        Spec<Task> spec = new Spec<Task>() {
            public boolean isSatisfiedBy(Task element) {
                return element != a;
            }
        };

        taskExecuter.useFilter(spec);
        taskExecuter.addTasks(toList(c));
        assertThat(taskExecuter.getAllTasks(), equalTo(toList(b, c)));
        
        taskExecuter.execute(failureHandler);
                
        assertThat(executedTasks, equalTo(toList(b, c)));
    }

    @Test
    public void willExecuteATaskWhoseDependenciesHaveBeenFilteredAfterAnotherHasFailed() {
        final RuntimeException failure = new RuntimeException();
        final Task a = brokenTask("a", failure);
        final Task b = task("b");
        final Task c = task("c", b);

        taskExecuter.useFilter(new Spec<Task>() {
            public boolean isSatisfiedBy(Task element) {
                return element != b;
            }
        });
        taskExecuter.addTasks(toList(a, c));

        context.checking(new Expectations() {{
            allowing(failureHandler).onTaskFailure(a);
            will(returnValue(true));
        }});
        taskExecuter.execute(failureHandler);

        assertThat(executedTasks, equalTo(toList(a, c)));
    }

    private void dependsOn(final Task task, final Task... dependsOn) {
        context.checking(new Expectations() {{
            TaskDependency taskDependency = context.mock(TaskDependency.class);
            allowing(task).getTaskDependencies();
            will(returnValue(taskDependency));
            allowing(taskDependency).getDependencies(task);
            will(returnValue(toSet(dependsOn)));
        }});
    }
    
    private Task brokenTask(String name, final RuntimeException failure, final Task... dependsOn) {
        final TaskInternal task = createTask(name);
        dependsOn(task, dependsOn);
        context.checking(new Expectations() {{
            atMost(1).of(task).executeWithoutThrowingTaskFailure();
            will(new ExecuteTaskAction(task));
            allowing(task.getState()).getFailure();
            will(returnValue(failure));
            allowing(task.getState()).rethrowFailure();
            will(throwException(failure));
        }});
        return task;
    }
    
    private Task task(final String name, final Task... dependsOn) {
        final TaskInternal task = createTask(name);
        dependsOn(task, dependsOn);
        context.checking(new Expectations() {{
            atMost(1).of(task).executeWithoutThrowingTaskFailure();
            will(new ExecuteTaskAction(task));
            allowing(task.getState()).getFailure();
            will(returnValue(null));
        }});
        return task;
    }
    
    private TaskInternal createTask(final String name) {
        final TaskInternal task = context.mock(TaskInternal.class);
        context.checking(new Expectations() {{
            TaskStateInternal state = context.mock(TaskStateInternal.class);

            allowing(task).getName();
            will(returnValue(name));
            allowing(task).getPath();
            will(returnValue(":" + name));
            allowing(task).getState();
            will(returnValue(state));
            allowing(task).compareTo(with(notNullValue(TaskInternal.class)));
            will(new org.jmock.api.Action() {
                public Object invoke(Invocation invocation) throws Throwable {
                    return name.compareTo(((Task) invocation.getParameter(0)).getName());
                }

                public void describeTo(Description description) {
                    description.appendText("compare to");
                }
            });
        }});

        return task;
    }

    private class ExecuteTaskAction implements org.jmock.api.Action {
        private final TaskInternal task;

        public ExecuteTaskAction(TaskInternal task) {
            this.task = task;
        }

        public Object invoke(Invocation invocation) throws Throwable {
            executedTasks.add(task);
            return null;
        }

        public void describeTo(Description description) {
            description.appendText("execute task");
        }
    }
}
