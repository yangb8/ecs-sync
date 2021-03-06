/*
 * Copyright 2013-2016 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.ecs.sync.storage;

import com.emc.ecs.sync.EcsSync;
import com.emc.ecs.sync.config.SyncConfig;
import com.emc.ecs.sync.config.SyncOptions;
import com.emc.ecs.sync.config.storage.FilesystemConfig;
import com.emc.ecs.sync.config.storage.TestConfig;
import com.emc.ecs.sync.model.ObjectMetadata;
import com.emc.ecs.sync.model.SyncObject;
import com.emc.ecs.sync.util.RandomInputStream;
import com.emc.ecs.sync.util.Iso8601Util;
import com.emc.util.StreamUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.nio.file.Files;
import java.util.Date;

public class FilesystemTest {
    private File sourceDir;
    private File targetDir;

    @Before
    public void setup() throws Exception {
        sourceDir = Files.createTempDirectory("ecs-sync-filesystem-source-test").toFile();
        if (!sourceDir.exists() || !sourceDir.isDirectory()) throw new RuntimeException("unable to make source dir");
        targetDir = Files.createTempDirectory("ecs-sync-filesystem-target-test").toFile();
        if (!targetDir.exists() || !targetDir.isDirectory()) throw new RuntimeException("unable to make target dir");
    }

    @After
    public void teardown() throws Exception {
        for (File file : sourceDir.listFiles()) {
            file.delete();
        }
        sourceDir.delete();
        for (File file : targetDir.listFiles()) {
            file.delete();
        }
        targetDir.delete();
    }

    @Test
    public void testModifiedSince() throws Exception {
        final File tempDir = new File("/tmp/ecs-sync-filesystem-source-test"); // File.createTempFile("ecs-sync-filesystem-test", "dir");
        tempDir.mkdir();
        tempDir.deleteOnExit();

        if (!tempDir.exists() || !tempDir.isDirectory())
            throw new RuntimeException("unable to make temp dir");

        try {
            // write 10 files
            int size = 10 * 1024;
            for (int i = 0; i < 10; i++) {
                StreamUtil.copy(new RandomInputStream(size), new FileOutputStream(new File(tempDir, "file-" + i)), size);
            }

            SyncOptions options = new SyncOptions().withThreadCount(16).withVerify(true);

            // sync 10 files to a test target
            FilesystemConfig fsConfig = new FilesystemConfig();
            fsConfig.setPath(tempDir.getPath());

            TestConfig testConfig = new TestConfig().withReadData(true).withDiscardData(false);

            EcsSync sync = new EcsSync();
            sync.setSyncConfig(new SyncConfig().withOptions(options).withSource(fsConfig).withTarget(testConfig));
            sync.run();

            Assert.assertEquals(0, sync.getStats().getObjectsFailed());
            Assert.assertEquals(10, sync.getStats().getObjectsComplete());

            // get time
            Date modifiedSince = new Date();

            // wait a tick to make sure mtime will be newer
            Thread.sleep(1000);

            // write 5 more files
            for (int i = 10; i < 15; i++) {
                StreamUtil.copy(new RandomInputStream(size), new FileOutputStream(new File(tempDir, "file-" + i)), size);
            }

            // sync using modifiedSince
            fsConfig.setModifiedSince(Iso8601Util.format(modifiedSince));

            sync = new EcsSync();
            sync.setSyncConfig(new SyncConfig().withOptions(options).withSource(fsConfig).withTarget(testConfig));
            sync.run();

            Assert.assertEquals(0, sync.getStats().getObjectsFailed());
            Assert.assertEquals(5, sync.getStats().getObjectsComplete());

            TestStorage testStorage = (TestStorage) sync.getTarget();

            for (SyncObject object : testStorage.getRootObjects()) {
                Assert.assertTrue("unmodified file was synced: " + object.getRelativePath(),
                        object.getRelativePath().matches("^file-1[0-4]$"));
            }
        } finally {
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
            new File(tempDir, ObjectMetadata.METADATA_DIR).delete(); // delete this so the temp dir can go away
        }
    }

    @Test
    public void testExcludeFilter() throws Exception {
        File file = new File(".");

        FilesystemConfig fsConfig = new FilesystemConfig();
        fsConfig.setPath(file.getPath());
        fsConfig.setExcludedPaths(new String[]{"(.*/)?\\.[^/]*", "(.*/)?[^/]*foo[^/]*", "(.*/)?[^/]*\\.bin"});

        FilesystemStorage storage = new FilesystemStorage();
        storage.setConfig(fsConfig);
        storage.configure(storage, null, null);

        FilenameFilter filter = storage.getFilter();

        String[] positiveTests = new String[]{"bar.txt", "a.out", "this has spaces", "n.o.t.h.i.n.g"};
        for (String test : positiveTests) {
            Assert.assertTrue("filter should have accepted " + test, filter.accept(file, test));
        }

        String[] negativeTests = new String[]{".svn", ".snapshots", ".f.o.o", "foo.txt", "ffoobar", "mywarez.bin",
                "in.the.round.bin"};
        for (String test : negativeTests) {
            Assert.assertFalse("filter should have rejected " + test, filter.accept(file, test));
        }
    }
}
