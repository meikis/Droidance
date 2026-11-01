// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class SidecarDisplayContentTest {
    public static class TaskInfo {
        public int displayId;
        public int numActivities;
        TaskInfo(int displayId, int numActivities) {
            this.displayId = displayId;
            this.numActivities = numActivities;
        }
    }

    public static class StackInfo {
        public int displayId = 7;
        public int[] taskIds;
        StackInfo(int... taskIds) { this.taskIds = taskIds; }
    }

    public static class RootTask {
        public int taskId = 12;
    }

    public static class MissingCount {
        public int displayId = 7;
    }

    @Test public void emptyRootTasksAndRootsWithoutActivitiesAreEmpty() throws Exception {
        assertEquals(0, SidecarDisplayContent.activityCount(Collections.emptyList(), 7));
        assertEquals(0, SidecarDisplayContent.activityCount(
                Arrays.asList(new TaskInfo(7, 0), new TaskInfo(7, 0)), 7));
    }

    @Test public void countsActivitiesAcrossAllRootTasks() throws Exception {
        assertEquals(4, SidecarDisplayContent.activityCount(
                Arrays.asList(new TaskInfo(7, 1), new TaskInfo(7, 3)), 7));
    }

    @Test public void androidElevenStacksCountTheirTasksAndExposeTheTopOne() throws Exception {
        assertEquals(3, SidecarDisplayContent.activityCount(
                Arrays.asList(new StackInfo(1, 2), new StackInfo(3)), 7));
        assertEquals(0, SidecarDisplayContent.activityCount(Collections.singletonList(new StackInfo()), 7));
        assertEquals(2, SidecarDisplayContent.topTaskId(new StackInfo(1, 2)));
        assertEquals(12, SidecarDisplayContent.topTaskId(new RootTask()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void stackWithoutTasksCannotBeFocused() throws Exception {
        SidecarDisplayContent.topTaskId(new StackInfo());
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingQueryResultIsNotAnEmptyInventory() throws Exception {
        SidecarDisplayContent.activityCount(null, 7);
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongDisplayCannotBeClassifiedAsEmpty() throws Exception {
        SidecarDisplayContent.activityCount(Collections.singletonList(new TaskInfo(8, 0)), 7);
    }

    @Test(expected = NoSuchFieldException.class)
    public void missingPlatformCountFieldCannotBeClassifiedAsEmpty() throws Exception {
        SidecarDisplayContent.activityCount(Collections.singletonList(new MissingCount()), 7);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeActivityCountCannotBeClassifiedAsEmpty() throws Exception {
        SidecarDisplayContent.activityCount(Collections.singletonList(new TaskInfo(7, -1)), 7);
    }
}
