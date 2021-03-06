package com.emc.ecs.sync.storage.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.PersistableTransfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerConfiguration;
import com.amazonaws.services.s3.transfer.Upload;
import com.amazonaws.services.s3.transfer.internal.S3ProgressListener;
import com.emc.ecs.sync.config.ConfigurationException;
import com.emc.ecs.sync.config.storage.AwsS3Config;
import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.Checksum;
import com.emc.ecs.sync.model.ObjectAcl;
import com.emc.ecs.sync.model.ObjectSummary;
import com.emc.ecs.sync.model.SyncObject;
import com.emc.ecs.sync.storage.AbstractFilesystemStorage;
import com.emc.ecs.sync.storage.AbstractStorage;
import com.emc.ecs.sync.storage.ObjectNotFoundException;
import com.emc.ecs.sync.storage.SyncStorage;
import com.emc.ecs.sync.util.Function;
import com.emc.ecs.sync.util.LazyValue;
import com.emc.ecs.sync.util.PerformanceWindow;
import com.emc.ecs.sync.util.ReadOnlyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.Executors;

public class AwsS3Storage extends AbstractStorage<AwsS3Config> implements S3Storage {
    private static final Logger log = LoggerFactory.getLogger(AwsS3Storage.class);

    // Invalid for metadata names
    private static final char[] HTTP_SEPARATOR_CHARS = new char[]{
            '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', ' ', '\t'};

    private static final String ACL_GROUP_TYPE = "Group";
    private static final String ACL_CANONICAL_USER_TYPE = "Canonical User";

    private static final String TYPE_DIRECTORY = "application/x-directory";

    private static final int MAX_PUT_SIZE_MB = 5 * 1024; // 5GB
    private static final int MIN_PART_SIZE_MB = 5;

    // timed operations
    private static final String OPERATION_LIST_OBJECTS = "S3ListObjects";
    private static final String OPERATION_LIST_VERSIONS = "S3ListVersions";
    private static final String OPERATION_HEAD_OBJECT = "S3HeadObject";
    private static final String OPERATION_GET_ACL = "S3GetAcl";
    private static final String OPERATION_OPEN_DATA_STREAM = "S3OpenDataStream";
    private static final String OPERATION_DELETE_OBJECTS = "S3DeleteObjects";
    private static final String OPERATION_DELETE_OBJECT = "S3DeleteObject";
    private static final String OPERATION_UPDATE_METADATA = "S3UpdateMetadata";

    private AmazonS3 s3;
    private PerformanceWindow sourceReadWindow;

    @Override
    public void configure(SyncStorage source, Iterator<SyncFilter> filters, SyncStorage target) {
        super.configure(source, filters, target);

        Assert.hasText(config.getAccessKey(), "accessKey is required");
        Assert.hasText(config.getSecretKey(), "secretKey is required");
        Assert.hasText(config.getBucketName(), "bucketName is required");
        Assert.isTrue(config.getBucketName().matches("[A-Za-z0-9._-]+"), config.getBucketName() + " is not a valid bucket name");

        AWSCredentials creds = new BasicAWSCredentials(config.getAccessKey(), config.getSecretKey());
        ClientConfiguration cc = new ClientConfiguration();

        if (config.getProtocol() != null)
            cc.setProtocol(Protocol.valueOf(config.getProtocol().toString().toUpperCase()));

        if (config.isLegacySignatures()) cc.setSignerOverride("S3SignerType");

        if (config.getSocketTimeoutMs() >= 0) cc.setSocketTimeout(config.getSocketTimeoutMs());

        s3 = new AmazonS3Client(creds, cc);

        if (config.getHost() != null) {
            String portStr = "";
            if (config.getPort() > 0) portStr = ":" + config.getPort();
            s3.setEndpoint(config.getHost() + portStr);
        }

        if (config.isDisableVHosts()) {
            log.info("The use of virtual hosted buckets has been DISABLED.  Path style buckets will be used.");
            S3ClientOptions opts = new S3ClientOptions();
            opts.setPathStyleAccess(true);
            s3.setS3ClientOptions(opts);
        }

        boolean bucketExists = s3.doesBucketExist(config.getBucketName());

        boolean bucketHasVersions = false;
        if (bucketExists) {
            // check if versioning has ever been enabled on the bucket (versions will not be collected unless required)
            BucketVersioningConfiguration versioningConfig = s3.getBucketVersioningConfiguration(config.getBucketName());
            List<String> versionedStates = Arrays.asList(BucketVersioningConfiguration.ENABLED, BucketVersioningConfiguration.SUSPENDED);
            bucketHasVersions = versionedStates.contains(versioningConfig.getStatus());
        }

        if (config.getKeyPrefix() == null) config.setKeyPrefix(""); // make sure keyPrefix isn't null

        if (target == this) {
            // create bucket if it doesn't exist
            if (!bucketExists && config.isCreateBucket()) {
                s3.createBucket(config.getBucketName());
                bucketExists = true;
                if (config.isIncludeVersions()) {
                    s3.setBucketVersioningConfiguration(new SetBucketVersioningConfigurationRequest(config.getBucketName(),
                            new BucketVersioningConfiguration(BucketVersioningConfiguration.ENABLED)));
                    bucketHasVersions = true;
                }
            }

            // make sure MPU settings are valid
            if (config.getMpuThresholdMb() > MAX_PUT_SIZE_MB) {
                log.warn("{}MB is above the maximum PUT size of {}MB. the maximum will be used instead",
                        config.getMpuThresholdMb(), MAX_PUT_SIZE_MB);
                config.setMpuThresholdMb(MAX_PUT_SIZE_MB);
            }
            if (config.getMpuPartSizeMb() < MIN_PART_SIZE_MB) {
                log.warn("{}MB is below the minimum MPU part size of {}MB. the minimum will be used instead",
                        config.getMpuPartSizeMb(), MIN_PART_SIZE_MB);
                config.setMpuPartSizeMb(MIN_PART_SIZE_MB);
            }

            if (source != null) sourceReadWindow = source.getReadWindow();
        }

        // make sure bucket exists
        if (!bucketExists)
            throw new ConfigurationException("The bucket " + config.getBucketName() + " does not exist.");

        // if syncing versions, make sure plugins support it and bucket has versioning enabled
        if (config.isIncludeVersions()) {
            if (!(source instanceof S3Storage && target instanceof S3Storage))
                throw new ConfigurationException("Version migration is only supported between two S3 plugins");

            if (!bucketHasVersions)
                throw new ConfigurationException("The specified bucket does not have versioning enabled.");
        }
    }

    @Override
    public String getRelativePath(String identifier, boolean directory) {
        String relativePath = identifier;
        if (relativePath.startsWith(config.getKeyPrefix()))
            relativePath = relativePath.substring(config.getKeyPrefix().length());
        if (config.isDecodeKeys()) relativePath = decodeKey(relativePath);
        // remove trailing slash from directories
        if (directory && relativePath.endsWith("/"))
            relativePath = relativePath.substring(0, relativePath.length() - 1);
        return relativePath;
    }

    private String decodeKey(String key) {
        try {
            return URLDecoder.decode(key, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 is not supported on this platform");
        }
    }

    @Override
    public String getIdentifier(String relativePath, boolean directory) {
        String identifier = config.getKeyPrefix() + relativePath;
        // append trailing slash for directories
        if (directory) identifier += "/";
        return identifier;
    }

    @Override
    protected ObjectSummary createSummary(final String identifier) throws ObjectNotFoundException {
        try {
            ObjectMetadata objectMetadata = getS3Metadata(identifier, null);
            return new ObjectSummary(identifier, false, objectMetadata.getContentLength());
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                if (config.isIncludeVersions()) {
                    // find the delete marker or throw ObjectNotFoundException
                    List<S3VersionSummary> versions = getS3Versions(identifier);
                    if (!versions.isEmpty()) {
                        S3VersionSummary lastVersion = versions.get(versions.size() - 1);
                        if (lastVersion.isLatest() && lastVersion.isDeleteMarker())
                            return new ObjectSummary(identifier, false, 0);
                    }
                }
                throw new ObjectNotFoundException(identifier);
            } else {
                throw e;
            }
        }
    }

    @Override
    public Iterable<ObjectSummary> allObjects() {
        if (config.isIncludeVersions()) {
            return new Iterable<ObjectSummary>() {
                @Override
                public Iterator<ObjectSummary> iterator() {
                    return new CombinedIterator<>(Arrays.asList(new PrefixIterator(config.getKeyPrefix()), new DeletedObjectIterator(config.getKeyPrefix())));
                }
            };
        } else {
            return new Iterable<ObjectSummary>() {
                @Override
                public Iterator<ObjectSummary> iterator() {
                    return new PrefixIterator(config.getKeyPrefix());
                }
            };
        }
    }

    // TODO: implement directoryMode, using prefix+delimiter
    @Override
    public Iterable<ObjectSummary> children(ObjectSummary parent) {
        return Collections.emptyList();
    }

    @Override
    public SyncObject loadObject(String identifier) throws ObjectNotFoundException {
        if (config.isIncludeVersions()) {
            List<S3ObjectVersion> objectVersions = loadVersions(identifier);
            if (!objectVersions.isEmpty()) {
                // use latest version as object (if we're here, it's a delete marker)
                S3ObjectVersion object = objectVersions.get(objectVersions.size() - 1);

                object.setProperty(PROP_OBJECT_VERSIONS, objectVersions);

                return object;
            }
            throw new ObjectNotFoundException(identifier);
        } else {
            return loadObject(identifier, null);
        }
    }

    private SyncObject loadObject(final String key, final String versionId) throws ObjectNotFoundException {
        // load metadata
        com.emc.ecs.sync.model.ObjectMetadata metadata;
        try {
            metadata = syncMetaFromS3Meta(getS3Metadata(key, versionId));
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                throw new ObjectNotFoundException(key + (versionId == null ? "" : " (versionId=" + versionId + ")"));
            } else {
                throw e;
            }
        }

        SyncObject object;
        if (versionId == null) {
            object = new SyncObject(this, getRelativePath(key, metadata.isDirectory()), metadata);
        } else {
            object = new S3ObjectVersion(this, getRelativePath(key, metadata.isDirectory()), metadata);
        }

        object.setLazyAcl(new LazyValue<ObjectAcl>() {
            @Override
            public ObjectAcl get() {
                return syncAclFromS3Acl(getS3Acl(key, versionId));
            }
        });

        object.setLazyStream(new LazyValue<InputStream>() {
            @Override
            public InputStream get() {
                return getS3DataStream(key, versionId);
            }
        });

        return object;
    }

    private List<S3ObjectVersion> loadVersions(final String key) {
        List<S3ObjectVersion> versions = new ArrayList<>();

        boolean directory = false; // delete markers won't have any metadata, so keep track of directory status
        for (S3VersionSummary summary : getS3Versions(key)) {
            S3ObjectVersion version;
            if (summary.isDeleteMarker()) {
                version = new S3ObjectVersion(this, getRelativePath(key, directory),
                        new com.emc.ecs.sync.model.ObjectMetadata().withModificationTime(summary.getLastModified())
                                .withContentLength(0).withDirectory(directory));
            } else {
                version = (S3ObjectVersion) loadObject(key, summary.getVersionId());
                directory = version.getMetadata().isDirectory();
            }
            version.setVersionId(summary.getVersionId());
            version.setETag(summary.getETag());
            version.setLatest(summary.isLatest());
            version.setDeleteMarker(summary.isDeleteMarker());
            versions.add(version);
        }

        Collections.sort(versions, new VersionComparator());

        return versions;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateObject(String identifier, SyncObject object) {
        try {
            // skip the root of the bucket since it obviously exists
            if ("".equals(config.getKeyPrefix() + object.getRelativePath())) {
                log.debug("Target is bucket root; skipping");
                return;
            }

            // check early on to see if we should ignore directories
            if (!config.isPreserveDirectories() && object.getMetadata().isDirectory()) {
                log.debug("Source is directory and preserveDirectories is false; skipping");
                return;
            }

            List<S3ObjectVersion> sourceVersionList = (List<S3ObjectVersion>) object.getProperty(PROP_OBJECT_VERSIONS);
            if (config.isIncludeVersions() && sourceVersionList != null) {
                ListIterator<S3ObjectVersion> sourceVersions = sourceVersionList.listIterator();
                ListIterator<S3ObjectVersion> targetVersions = loadVersions(identifier).listIterator();

                boolean newVersions = false, replaceVersions = false;
                if (options.isForceSync()) {
                    replaceVersions = true;
                } else {

                    // special workaround for bug where objects are listed, but they have no versions
                    if (sourceVersions.hasNext()) {

                        // check count and etag/delete-marker to compare version chain
                        while (sourceVersions.hasNext()) {
                            S3ObjectVersion sourceVersion = sourceVersions.next();

                            if (targetVersions.hasNext()) {
                                S3ObjectVersion targetVersion = targetVersions.next();

                                if (sourceVersion.isDeleteMarker()) {

                                    if (!targetVersion.isDeleteMarker()) replaceVersions = true;
                                } else {

                                    if (targetVersion.isDeleteMarker()) replaceVersions = true;

                                    else if (!sourceVersion.getETag().equals(targetVersion.getETag()))
                                        replaceVersions = true; // different checksum
                                }

                            } else if (!replaceVersions) { // source has new versions, but existing target versions are ok
                                newVersions = true;
                                sourceVersions.previous(); // back up one
                                putIntermediateVersions(sourceVersions, identifier); // add any new intermediary versions (current is added below)
                            }
                        }

                        if (targetVersions.hasNext()) replaceVersions = true; // target has more versions

                        if (!newVersions && !replaceVersions) {
                            log.info("Source and target versions are the same. Skipping {}", object.getRelativePath());
                            return;
                        }
                    }
                }

                // something's off; must delete all versions of the object
                if (replaceVersions) {
                    log.info("[{}]: version history differs between source and target; re-placing target version history with that from source.",
                            object.getRelativePath());

                    // collect versions in target
                    final List<DeleteObjectsRequest.KeyVersion> deleteVersions = new ArrayList<>();
                    while (targetVersions.hasNext()) targetVersions.next(); // move cursor to end
                    while (targetVersions.hasPrevious()) { // go in reverse order
                        S3ObjectVersion version = targetVersions.previous();
                        deleteVersions.add(new DeleteObjectsRequest.KeyVersion(identifier, version.getVersionId()));
                    }

                    // batch delete all versions in target
                    log.debug("[{}]: deleting all versions in target", object.getRelativePath());
                    if (!deleteVersions.isEmpty()) {
                        time(new Function<Void>() {
                            @Override
                            public Void call() {
                                s3.deleteObjects(new DeleteObjectsRequest(config.getBucketName()).withKeys(deleteVersions));
                                return null;
                            }
                        }, OPERATION_DELETE_OBJECTS);
                    }

                    // replay version history in target
                    while (sourceVersions.hasPrevious()) sourceVersions.previous(); // move cursor to beginning
                    putIntermediateVersions(sourceVersions, identifier);
                }
            }

            // at this point we know we are going to write the object
            // Put [current object version]
            if (object instanceof S3ObjectVersion && ((S3ObjectVersion) object).isDeleteMarker()) {

                // object has version history, but is currently deleted
                log.debug("[{}]: deleting object in target to replicate delete marker in source.", object.getRelativePath());
                final String fIdentifier = identifier;
                time(new Function<Void>() {
                    @Override
                    public Void call() {
                        s3.deleteObject(config.getBucketName(), fIdentifier);
                        return null;
                    }
                }, OPERATION_DELETE_OBJECT);
            } else {
                putObject(object, identifier);

                // if object has new metadata after the stream (i.e. encryption checksum), we must update S3 again
                if (object.isPostStreamUpdateRequired()) {
                    log.debug("[{}]: updating metadata after sync as required", object.getRelativePath());
                    final CopyObjectRequest cReq = new CopyObjectRequest(config.getBucketName(), identifier, config.getBucketName(), identifier);
                    cReq.setNewObjectMetadata(s3MetaFromSyncMeta(object.getMetadata()));
                    time(new Function<Void>() {
                        @Override
                        public Void call() {
                            s3.copyObject(cReq);
                            return null;
                        }
                    }, OPERATION_UPDATE_METADATA);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store object: " + e, e);
        }
    }

    private void putIntermediateVersions(ListIterator<S3ObjectVersion> versions, String key) {
        while (versions.hasNext()) {
            S3ObjectVersion version = versions.next();
            try {
                if (!version.isLatest()) {
                    // source has more versions; add any non-current versions that are missing from the target
                    // (current version will be added below)
                    if (version.isDeleteMarker()) {
                        log.debug("[{}#{}]: deleting object in target to replicate delete marker in source.",
                                key, version.getVersionId());
                        s3.deleteObject(config.getBucketName(), key);
                    } else {
                        log.debug("[{}#{}]: replicating historical version in target.",
                                key, version.getVersionId());
                        putObject(version, key);
                    }
                }
            } catch (RuntimeException e) {
                throw new RuntimeException(String.format("sync of historical version %s failed", version.getVersionId()), e);
            }
        }
    }

    private void putObject(SyncObject obj, String targetKey) {
        ObjectMetadata om = s3MetaFromSyncMeta(obj.getMetadata());
        if (obj.getMetadata().isDirectory()) om.setContentType(TYPE_DIRECTORY);

        PutObjectRequest req;
        File file = (File) obj.getProperty(AbstractFilesystemStorage.PROP_FILE);
        S3ProgressListener progressListener = null;
        if (obj.getMetadata().isDirectory()) {
            req = new PutObjectRequest(config.getBucketName(), targetKey, new ByteArrayInputStream(new byte[0]), om);
        } else if (file != null) {
            req = new PutObjectRequest(config.getBucketName(), targetKey, file).withMetadata(om);
            progressListener = new SourceReadOverrideListener(obj);
        } else {
            req = new PutObjectRequest(config.getBucketName(), targetKey, obj.getDataStream(), om);
        }

        if (options.isSyncAcl())
            req.setAccessControlList(s3AclFromSyncAcl(obj.getAcl(), options.isIgnoreInvalidAcls()));

        // xfer manager will figure out if MPU is needed (based on threshold), do the MPU if necessary,
        // and abort if it fails
        TransferManagerConfiguration xferConfig = new TransferManagerConfiguration();
        xferConfig.setMultipartUploadThreshold((long) config.getMpuThresholdMb() * 1024 * 1024);
        xferConfig.setMinimumUploadPartSize((long) config.getMpuPartSizeMb() * 1024 * 1024);
        TransferManager xferManager = new TransferManager(s3, Executors.newFixedThreadPool(config.getMpuThreadCount()));
        xferManager.setConfiguration(xferConfig);

        // directly update

        Upload upload = xferManager.upload(req, progressListener);
        try {
            log.debug("Wrote {}, etag: {}", targetKey, upload.waitForUploadResult().getETag());
        } catch (InterruptedException e) {
            throw new RuntimeException("upload thread was interrupted", e);
        }
    }

    @Override
    public void delete(final String identifier) {
        time(new Function<Void>() {
            @Override
            public Void call() {
                s3.deleteObject(config.getBucketName(), identifier);
                return null;
            }
        }, OPERATION_DELETE_OBJECT);
    }

    // COMMON S3 CALLS

    private ObjectMetadata getS3Metadata(final String key, final String versionId) {
        return time(new Function<ObjectMetadata>() {
            @Override
            public ObjectMetadata call() {
                GetObjectMetadataRequest request = new GetObjectMetadataRequest(config.getBucketName(), key, versionId);
                return s3.getObjectMetadata(request);
            }
        }, OPERATION_HEAD_OBJECT);
    }

    private AccessControlList getS3Acl(final String key, final String versionId) {
        return time(new Function<AccessControlList>() {
            @Override
            public AccessControlList call() {
                if (versionId == null) return s3.getObjectAcl(config.getBucketName(), key);
                else return s3.getObjectAcl(config.getBucketName(), key, versionId);
            }
        }, OPERATION_GET_ACL);
    }

    private InputStream getS3DataStream(final String key, final String versionId) {
        return time(new Function<InputStream>() {
            @Override
            public InputStream call() {
                GetObjectRequest request = new GetObjectRequest(config.getBucketName(), key, versionId);
                return s3.getObject(request).getObjectContent();
            }
        }, OPERATION_OPEN_DATA_STREAM);
    }

    private List<S3VersionSummary> getS3Versions(final String key) {
        List<S3VersionSummary> versions = new ArrayList<>();

        VersionListing listing = null;
        do {
            final VersionListing fListing = listing;
            listing = time(new Function<VersionListing>() {
                @Override
                public VersionListing call() {
                    if (fListing == null) {
                        return s3.listVersions(config.getBucketName(), key, null, null, "/", null);
                    } else {
                        return s3.listNextBatchOfVersions(fListing);
                    }
                }
            }, OPERATION_LIST_VERSIONS);
            listing.setMaxKeys(1000); // Google Storage compatibility

            for (final S3VersionSummary summary : listing.getVersionSummaries()) {
                if (summary.getKey().equals(key)) versions.add(summary);
            }
        } while (listing.isTruncated());

        return versions;
    }

    // READ TRANSLATION METHODS

    private boolean isDirectoryPlaceholder(String contentType, long size) {
        return TYPE_DIRECTORY.equals(contentType) && size == 0;
    }

    private com.emc.ecs.sync.model.ObjectMetadata syncMetaFromS3Meta(ObjectMetadata s3meta) {
        com.emc.ecs.sync.model.ObjectMetadata meta = new com.emc.ecs.sync.model.ObjectMetadata();

        meta.setDirectory(isDirectoryPlaceholder(s3meta.getContentType(), s3meta.getContentLength()));
        meta.setCacheControl(s3meta.getCacheControl());
        meta.setContentDisposition(s3meta.getContentDisposition());
        meta.setContentEncoding(s3meta.getContentEncoding());
        if (s3meta.getContentMD5() != null) meta.setChecksum(new Checksum("MD5", s3meta.getContentMD5()));
        meta.setContentType(s3meta.getContentType());
        meta.setHttpExpires(s3meta.getHttpExpiresDate());
        meta.setExpirationDate(s3meta.getExpirationTime());
        meta.setModificationTime(s3meta.getLastModified());
        meta.setContentLength(s3meta.getContentLength());
        meta.setUserMetadata(toMetaMap(s3meta.getUserMetadata()));

        return meta;
    }

    private Map<String, com.emc.ecs.sync.model.ObjectMetadata.UserMetadata> toMetaMap(Map<String, String> sourceMap) {
        Map<String, com.emc.ecs.sync.model.ObjectMetadata.UserMetadata> metaMap = new HashMap<>();
        for (String key : sourceMap.keySet()) {
            metaMap.put(key, new com.emc.ecs.sync.model.ObjectMetadata.UserMetadata(key, sourceMap.get(key)));
        }
        return metaMap;
    }

    private ObjectAcl syncAclFromS3Acl(AccessControlList s3Acl) {
        ObjectAcl syncAcl = new ObjectAcl();
        syncAcl.setOwner(s3Acl.getOwner().getId());
        for (Grant grant : s3Acl.getGrantsAsList()) {
            Grantee grantee = grant.getGrantee();
            if (grantee instanceof GroupGrantee || grantee.getTypeIdentifier().equals(ACL_GROUP_TYPE))
                syncAcl.addGroupGrant(grantee.getIdentifier(), grant.getPermission().toString());
            else if (grantee instanceof CanonicalGrantee || grantee.getTypeIdentifier().equals(ACL_CANONICAL_USER_TYPE))
                syncAcl.addUserGrant(grantee.getIdentifier(), grant.getPermission().toString());
        }
        return syncAcl;
    }

    // WRITE TRANSLATION METHODS

    private AccessControlList s3AclFromSyncAcl(ObjectAcl syncAcl, boolean ignoreInvalid) {
        AccessControlList s3Acl = new AccessControlList();

        s3Acl.setOwner(new Owner(syncAcl.getOwner(), syncAcl.getOwner()));

        for (String user : syncAcl.getUserGrants().keySet()) {
            Grantee grantee = new CanonicalGrantee(user);
            for (String permission : syncAcl.getUserGrants().get(user)) {
                Permission perm = getS3Permission(permission, ignoreInvalid);
                if (perm != null) s3Acl.grantPermission(grantee, perm);
            }
        }

        for (String group : syncAcl.getGroupGrants().keySet()) {
            Grantee grantee = GroupGrantee.parseGroupGrantee(group);
            if (grantee == null) {
                if (ignoreInvalid)
                    log.warn("{} is not a valid S3 group", group);
                else
                    throw new RuntimeException(group + " is not a valid S3 group");
            }
            for (String permission : syncAcl.getGroupGrants().get(group)) {
                Permission perm = getS3Permission(permission, ignoreInvalid);
                if (perm != null) s3Acl.grantPermission(grantee, perm);
            }
        }

        return s3Acl;
    }

    private ObjectMetadata s3MetaFromSyncMeta(com.emc.ecs.sync.model.ObjectMetadata syncMeta) {
        com.amazonaws.services.s3.model.ObjectMetadata om = new com.amazonaws.services.s3.model.ObjectMetadata();
        if (syncMeta.getCacheControl() != null) om.setCacheControl(syncMeta.getCacheControl());
        if (syncMeta.getContentDisposition() != null) om.setContentDisposition(syncMeta.getContentDisposition());
        if (syncMeta.getContentEncoding() != null) om.setContentEncoding(syncMeta.getContentEncoding());
        om.setContentLength(syncMeta.getContentLength());
        if (syncMeta.getChecksum() != null && syncMeta.getChecksum().getAlgorithm().equals("MD5"))
            om.setContentMD5(syncMeta.getChecksum().getValue());
        if (syncMeta.getContentType() != null) om.setContentType(syncMeta.getContentType());
        if (syncMeta.getHttpExpires() != null) om.setHttpExpiresDate(syncMeta.getHttpExpires());
        om.setUserMetadata(formatUserMetadata(syncMeta));
        if (syncMeta.getModificationTime() != null) om.setLastModified(syncMeta.getModificationTime());
        return om;
    }

    private Permission getS3Permission(String permission, boolean ignoreInvalid) {
        Permission s3Perm = Permission.parsePermission(permission);
        if (s3Perm == null) {
            if (ignoreInvalid)
                log.warn("{} is not a valid S3 permission", permission);
            else
                throw new RuntimeException(permission + " is not a valid S3 permission");
        }
        return s3Perm;
    }

    private Map<String, String> formatUserMetadata(com.emc.ecs.sync.model.ObjectMetadata metadata) {
        Map<String, String> s3meta = new HashMap<>();

        for (String key : metadata.getUserMetadata().keySet()) {
            s3meta.put(filterName(key), filterValue(metadata.getUserMetadataValue(key)));
        }

        return s3meta;
    }

    /**
     * S3 metadata names must be compatible with header naming.  Filter the names so
     * they're acceptable.
     * Per HTTP RFC:<br>
     * <pre>
     * token          = 1*<any CHAR except CTLs or separators>
     * separators     = "(" | ")" | "<" | ">" | "@"
     *                 | "," | ";" | ":" | "\" | <">
     *                 | "/" | "[" | "]" | "?" | "="
     *                 | "{" | "}" | SP | HT
     * <pre>
     *
     * @param name the header name to filter.
     * @return the metadata name filtered to be compatible with HTTP headers.
     */
    private String filterName(String name) {
        try {
            // First, filter out any non-ASCII characters.
            byte[] raw = name.getBytes("US-ASCII");
            String ascii = new String(raw, "US-ASCII");

            // Strip separator chars
            for (char sep : HTTP_SEPARATOR_CHARS) {
                ascii = ascii.replace(sep, '-');
            }

            return ascii;
        } catch (UnsupportedEncodingException e) {
            // should never happen
            throw new RuntimeException("Missing ASCII encoding", e);
        }
    }

    /**
     * S3 sends metadata as HTTP headers, unencoded.  Filter values to be compatible
     * with headers.
     */
    private String filterValue(String value) {
        try {
            // First, filter out any non-ASCII characters.
            byte[] raw = value.getBytes("US-ASCII");
            String ascii = new String(raw, "US-ASCII");

            // Make sure there's no newlines
            ascii = ascii.replace('\n', ' ');

            return ascii;
        } catch (UnsupportedEncodingException e) {
            // should never happen
            throw new RuntimeException("Missing ASCII encoding", e);
        }
    }

    private class PrefixIterator extends ReadOnlyIterator<ObjectSummary> {
        private String prefix;
        private ObjectListing listing;
        private Iterator<S3ObjectSummary> objectIterator;

        PrefixIterator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        protected ObjectSummary getNextObject() {
            if (listing == null || (!objectIterator.hasNext() && listing.isTruncated())) {
                getNextBatch();
            }

            if (objectIterator.hasNext()) {
                S3ObjectSummary summary = objectIterator.next();
                return new ObjectSummary(summary.getKey(), false, summary.getSize());
            }

            // list is not truncated and iterators are finished; no more objects
            return null;
        }

        private void getNextBatch() {
            if (listing == null) {
                listing = time(new Function<ObjectListing>() {
                    @Override
                    public ObjectListing call() {
                        if ("".equals(prefix)) {
                            return s3.listObjects(config.getBucketName());
                        } else {
                            return s3.listObjects(config.getBucketName(), prefix);
                        }
                    }
                }, OPERATION_LIST_OBJECTS);
            } else {
                listing = time(new Function<ObjectListing>() {
                    @Override
                    public ObjectListing call() {
                        return s3.listNextBatchOfObjects(listing);
                    }
                }, OPERATION_LIST_OBJECTS);
            }
            listing.setMaxKeys(1000); // Google Storage compatibility
            objectIterator = listing.getObjectSummaries().iterator();
        }
    }

    private class DeletedObjectIterator extends ReadOnlyIterator<ObjectSummary> {
        private String prefix;
        private VersionListing versionListing;
        private Iterator<S3VersionSummary> versionIterator;

        DeletedObjectIterator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        protected ObjectSummary getNextObject() {
            while (true) {
                S3VersionSummary versionSummary = getNextSummary();

                if (versionSummary == null) return null;

                if (versionSummary.isLatest() && versionSummary.isDeleteMarker())
                    return new ObjectSummary(versionSummary.getKey(), false, versionSummary.getSize());
            }
        }

        private S3VersionSummary getNextSummary() {
            // look for deleted objects in versioned bucket
            if (versionListing == null || (!versionIterator.hasNext() && versionListing.isTruncated())) {
                getNextVersionBatch();
            }

            if (versionIterator.hasNext()) {
                return versionIterator.next();
            }

            // no more versions
            return null;
        }

        private void getNextVersionBatch() {
            if (versionListing == null) {
                versionListing = time(new Function<VersionListing>() {
                    @Override
                    public VersionListing call() {
                        return s3.listVersions(config.getBucketName(), "".equals(prefix) ? null : prefix);
                    }
                }, OPERATION_LIST_VERSIONS);
            } else {
                versionListing.setMaxKeys(1000); // Google Storage compatibility
                versionListing = time(new Function<VersionListing>() {
                    @Override
                    public VersionListing call() {
                        return s3.listNextBatchOfVersions(versionListing);
                    }
                }, OPERATION_LIST_VERSIONS);
            }
            versionIterator = versionListing.getVersionSummaries().iterator();
        }
    }

    private class CombinedIterator<T> extends ReadOnlyIterator<T> {
        private List<? extends Iterator<T>> iterators;
        private int currentIterator = 0;

        CombinedIterator(List<? extends Iterator<T>> iterators) {
            this.iterators = iterators;
        }

        @Override
        protected T getNextObject() {
            while (currentIterator < iterators.size()) {
                if (iterators.get(currentIterator).hasNext()) return iterators.get(currentIterator).next();
                currentIterator++;
            }

            return null;
        }
    }

    private class VersionComparator implements Comparator<S3ObjectVersion> {
        @Override
        public int compare(S3ObjectVersion o1, S3ObjectVersion o2) {
            int result = o1.getMetadata().getModificationTime().compareTo(o2.getMetadata().getModificationTime());
            if (result == 0) result = o1.getVersionId().compareTo(o2.getVersionId());
            return result;
        }
    }

    private class SourceReadOverrideListener implements S3ProgressListener {
        private final SyncObject object;

        SourceReadOverrideListener(SyncObject object) {
            this.object = object;
        }

        @Override
        public void onPersistableTransfer(PersistableTransfer persistableTransfer) {
        }

        @Override
        public void progressChanged(com.amazonaws.event.ProgressEvent progressEvent) {
            if (progressEvent.getEventType() == ProgressEventType.REQUEST_BYTE_TRANSFER_EVENT) {
                if (sourceReadWindow != null) sourceReadWindow.increment(progressEvent.getBytesTransferred());
                synchronized (object) {
                    // these events will include XML payload for MPU (no way to differentiate)
                    // do not set bytesRead to more then the object size
                    object.setBytesRead(object.getBytesRead() + progressEvent.getBytesTransferred());
                    if (object.getBytesRead() > object.getMetadata().getContentLength())
                        object.setBytesRead(object.getMetadata().getContentLength());
                }
            }
        }
    }
}
