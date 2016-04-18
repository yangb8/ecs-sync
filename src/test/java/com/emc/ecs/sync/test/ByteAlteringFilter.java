/*
 * Copyright 2013-2015 EMC Corporation. All Rights Reserved.
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
package com.emc.ecs.sync.test;

import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.object.SyncObject;
import com.emc.ecs.sync.source.SyncSource;
import com.emc.ecs.sync.target.SyncTarget;
import com.emc.ecs.sync.util.CountingInputStream;
import com.emc.ecs.sync.util.DelegatingSyncObject;
import com.emc.ecs.sync.util.SyncUtil;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

import javax.xml.bind.DatatypeConverter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class ByteAlteringFilter extends SyncFilter {
    private AtomicInteger modifiedObjects = new AtomicInteger(0);
    private Random random = new Random();

    @Override
    public void filter(SyncObject obj) {
        getNext().filter(obj);
    }

    @Override
    public SyncObject reverseFilter(SyncObject obj) {
        obj = getNext().reverseFilter(obj);
        if (obj.getMetadata().getContentLength() > 0) { // won't work on zero-byte objects
            if (random.nextBoolean()) {
                modifiedObjects.incrementAndGet();
                obj = new AlteredObject(obj);
            }
        }
        return obj;
    }

    public int getModifiedObjects() {
        return modifiedObjects.intValue();
    }

    @Override
    public String getActivationName() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public String getDocumentation() {
        return null;
    }

    @Override
    public Options getCustomOptions() {
        return null;
    }

    @Override
    protected void parseCustomOptions(CommandLine line) {
    }

    @Override
    public void configure(SyncSource source, Iterator<SyncFilter> filters, SyncTarget target) {
    }

    private class AlteredObject<I> extends DelegatingSyncObject<I> {
        private CountingInputStream cin;
        private DigestInputStream din;
        private byte[] md5;

        public AlteredObject(SyncObject<I> delegate) {
            super(delegate);
        }

        @Override
        public synchronized InputStream getInputStream() {
            try {
                if (cin == null) {
                    din = new DigestInputStream(new AlteredStream(delegate.getInputStream()), MessageDigest.getInstance("MD5"));
                    cin = new CountingInputStream(din);
                }
                return cin;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("No MD5 digest?!");
            }
        }

        @Override
        public long getBytesRead() {
            if (cin != null) {
                return cin.getBytesRead();
            } else {
                return 0;
            }
        }

        @Override
        public void reset() {
            if (cin != null) {
                try {
                    cin.close();
                } catch (IOException e) {
                    // ignore
                }
                cin = null;
                din = null;
                md5 = null;
            }
            delegate.reset();
        }

        protected synchronized byte[] getMd5(boolean forceRead) {
            if (md5 == null) {
                getInputStream();
                if (!cin.isClosed()) {
                    if (!forceRead || cin.getBytesRead() > 0)
                        throw new IllegalStateException("Cannot call getMd5 until stream is closed");
                    SyncUtil.consumeAndCloseStream(cin);
                }
                md5 = din.getMessageDigest().digest();
            }
            return md5;
        }

        @Override
        public String getMd5Hex(boolean forceRead) {
            return DatatypeConverter.printHexBinary(getMd5(forceRead));
        }
    }

    private class AlteredStream extends FilterInputStream {
        public AlteredStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result == -1) return result;
            return ((byte) result) + 1;
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            return read(bytes, 0, bytes.length);
        }

        @Override
        public int read(byte[] bytes, int i, int i1) throws IOException {
            int result = super.read(bytes, i, i1);
            if (result > 0) bytes[i] += 1;
            return result;
        }
    }
}
