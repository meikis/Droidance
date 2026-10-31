// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.util.List;

/** Reads framework RootTaskInfo fields without treating missing or malformed data as an empty display. */
final class SidecarDisplayContent {
    private SidecarDisplayContent() {}

    static int activityCount(Object rootTasks, int displayId) throws ReflectiveOperationException {
        if (!(rootTasks instanceof List<?>)) {
            throw new IllegalArgumentException("Root task inventory unavailable");
        }
        int count = 0;
        for (Object task : (List<?>) rootTasks) {
            if (task == null || task.getClass().getField("displayId").getInt(task) != displayId) {
                throw new IllegalArgumentException("Root task display mismatch");
            }
            int activities = task.getClass().getField("numActivities").getInt(task);
            if (activities < 0) throw new IllegalArgumentException("Invalid activity count");
            count = Math.addExact(count, activities);
        }
        return count;
    }
}
