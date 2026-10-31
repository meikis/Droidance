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
