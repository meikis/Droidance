// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.lang.reflect.Method;
import java.util.List;

/** Reads framework RootTaskInfo fields without treating missing or malformed data as an empty display. */
final class SidecarDisplayContent {
    private SidecarDisplayContent() {}

    /** Android 11 exposes the same inventory as StackInfo; the field readers below accept both. */
    static List<?> rootTasks(int displayId) throws ReflectiveOperationException {
        Object manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class<?> managerClass = Class.forName("android.app.IActivityTaskManager");
        Method query;
        try {
            query = managerClass.getMethod("getAllRootTaskInfosOnDisplay", int.class);
        } catch (NoSuchMethodException stackInventory) {
            query = managerClass.getMethod("getAllStackInfosOnDisplay", int.class);
        }
        return (List<?>) query.invoke(manager, displayId);
    }

    static int topTaskId(Object rootTask) throws ReflectiveOperationException {
        try {
            return rootTask.getClass().getField("taskId").getInt(rootTask);
        } catch (NoSuchFieldException stackInfo) {
            int[] taskIds = stackTaskIds(rootTask);
            // Android 11 fills this array from bottom to top.
            if (taskIds.length == 0) throw new IllegalArgumentException("Root task has no tasks");
            return taskIds[taskIds.length - 1];
        }
    }

    static int activityCount(Object rootTasks, int displayId) throws ReflectiveOperationException {
        if (!(rootTasks instanceof List<?>)) {
            throw new IllegalArgumentException("Root task inventory unavailable");
        }
        int count = 0;
        for (Object task : (List<?>) rootTasks) {
            if (task == null || task.getClass().getField("displayId").getInt(task) != displayId) {
                throw new IllegalArgumentException("Root task display mismatch");
            }
            int activities = activities(task);
            if (activities < 0) throw new IllegalArgumentException("Invalid activity count");
            count = Math.addExact(count, activities);
        }
        return count;
    }

    private static int activities(Object task) throws ReflectiveOperationException {
        try {
            return task.getClass().getField("numActivities").getInt(task);
        } catch (NoSuchFieldException stackInfo) {
            return stackTaskIds(task).length;
        }
    }

    private static int[] stackTaskIds(Object stackInfo) throws ReflectiveOperationException {
        int[] taskIds = (int[]) stackInfo.getClass().getField("taskIds").get(stackInfo);
        if (taskIds == null) throw new IllegalArgumentException("Stack task inventory unavailable");
        return taskIds;
    }
}
