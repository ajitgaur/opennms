/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.threshd.shell;

import java.util.concurrent.CompletableFuture;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.netmgt.threshd.api.ThresholdStateMonitor;

@Command(scope = "opennms", name = "threshold-clear-all", description = "Clears all threshold states")
@Service
public class ClearAll extends AbstractThresholdStateCommand {
    @Reference
    ThresholdStateMonitor thresholdStateMonitor;

    @Option(name = "-p", aliases = "--persisted-only", description = "When set, clears only the persisted state")
    private boolean clearPersistedOnly;

    @Override
    public Object execute() throws InterruptedException {
        System.out.print("Clearing all thresholding states...");

        CompletableFuture<Void> clearFuture;

        if (clearPersistedOnly) {
            clearFuture = blobStore.truncateContextAsync(THRESHOLDING_KV_CONTEXT);
        } else {
            clearFuture = CompletableFuture.supplyAsync(() -> {
                thresholdStateMonitor.reinitializeStates();
                return null;
            });
        }

        while (!clearFuture.isDone()) {
            Thread.sleep(1000);
            System.out.print('.');
        }

        try {
            clearFuture.get();
            System.out.println("done");
        } catch (Exception e) {
            System.out.println("Failed to clear all states" + e);
        }

        return null;
    }
}