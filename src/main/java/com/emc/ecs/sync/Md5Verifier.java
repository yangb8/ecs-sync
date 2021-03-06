package com.emc.ecs.sync;

import com.emc.ecs.sync.model.SyncObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Md5Verifier implements SyncVerifier {
    private static final Logger log = LoggerFactory.getLogger(Md5Verifier.class);

    @Override
    public void verify(final SyncObject sourceObject, final SyncObject targetObject) {

        // this implementation only verifies data objects
        if (sourceObject.getMetadata().isDirectory()) {
            if (!targetObject.getMetadata().isDirectory())
                throw new RuntimeException("source is directory; target is not");
        } else {
            if (targetObject.getMetadata().isDirectory())
                throw new RuntimeException("source is data object; target is directory");

            // thread the streams for efficiency (in case of verify-only)
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> futureSourceMd5 = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return sourceObject.getMd5Hex(true);
                }
            });
            Future<String> futureTargetMd5 = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return targetObject.getMd5Hex(true);
                }
            });
            executor.shutdown();

            try {
                String sourceMd5 = futureSourceMd5.get(), targetMd5 = futureTargetMd5.get();

                if (!sourceMd5.equals(targetMd5))
                    throw new RuntimeException(String.format("MD5 sum mismatch (%s != %s)", sourceMd5, targetMd5));
                else
                    log.debug("MD5 sum verified ({} == {})", sourceMd5, targetMd5);

            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        }
    }
}
