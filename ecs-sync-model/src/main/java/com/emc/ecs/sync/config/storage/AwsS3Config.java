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
package com.emc.ecs.sync.config.storage;

import com.emc.ecs.sync.config.AbstractConfig;
import com.emc.ecs.sync.config.ConfigurationException;
import com.emc.ecs.sync.config.Protocol;
import com.emc.ecs.sync.config.annotation.*;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.emc.ecs.sync.config.storage.AwsS3Config.PATTERN_DESC;
import static com.emc.ecs.sync.config.storage.AwsS3Config.URI_PREFIX;

@XmlRootElement
@StorageConfig(uriPrefix = URI_PREFIX)
@Label("S3")
@Documentation("Represents storage in an Amazon S3 bucket. This " +
        "plugin is triggered by the pattern:\n" +
        PATTERN_DESC + "\n" +
        "Scheme, host and port are all optional. If omitted, " +
        "https://s3.amazonaws.com:443 is assumed. " +
        "keyPrefix (optional) is the prefix under which to start " +
        "enumerating or writing keys within the bucket, e.g. dir1/. If omitted, the " +
        "root of the bucket is assumed.")
public class AwsS3Config extends AbstractConfig {
    public static final String URI_PREFIX = "s3:";
    public static final Pattern URI_PATTERN = Pattern.compile("^" + URI_PREFIX + "(?:(http|https)://)?([^:]+):([a-zA-Z0-9\\+/=]+)@?(?:([^/:]+?)?(:[0-9]+)?)?/([^/]+)(?:/(.*))?$");
    public static final String PATTERN_DESC = URI_PREFIX + "[http[s]://]access_key:secret_key@[host[:port]][/root-prefix]";

    public static final int DEFAULT_MPU_THRESHOLD_MB = 512;
    public static final int DEFAULT_MPU_PART_SIZE_MB = 128;
    public static final int DEFAULT_MPU_THREAD_COUNT = 4;
    public static final int DEFAULT_SOCKET_TIMEOUT = 50000; // 50 secs
    public static final int MIN_PART_SIZE_MB = 5;

    private Protocol protocol;
    private String host;
    private int port = -1;
    private String accessKey;
    private String secretKey;
    private boolean disableVHosts;
    private String bucketName;
    private boolean createBucket;
    private String keyPrefix;
    private boolean decodeKeys;
    private boolean includeVersions;
    private boolean legacySignatures;
    private int mpuThresholdMb = DEFAULT_MPU_THRESHOLD_MB;
    private int mpuPartSizeMb = DEFAULT_MPU_PART_SIZE_MB;
    private int mpuThreadCount = DEFAULT_MPU_THREAD_COUNT;
    private int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT;
    private boolean preserveDirectories = true;

    @UriGenerator
    public String getUri() {
        String uri = URI_PREFIX;

        if (protocol != null) uri += protocol + "://";

        uri += String.format("%s:%s@", accessKey, secretKey);

        if (host != null) uri += host;
        if (port > 0) uri += ":" + port;

        uri += "/" + bucketName;

        if (keyPrefix != null) uri += "/" + keyPrefix;

        return uri;
    }

    @UriParser
    public void setUri(String uri) {
        Matcher m = URI_PATTERN.matcher(uri);
        if (!m.matches()) {
            throw new ConfigurationException(String.format("URI does not match %s pattern (%s)", URI_PREFIX, PATTERN_DESC));
        }


        if (m.group(1) != null) protocol = Protocol.valueOf(m.group(1).toLowerCase());
        host = m.group(4);
        port = -1;
        if (m.group(5) != null) port = Integer.parseInt(m.group(5).substring(1));

        accessKey = m.group(2);
        secretKey = m.group(3);

        bucketName = m.group(6);
        keyPrefix = m.group(7);

        if (accessKey == null || secretKey == null || bucketName == null)
            throw new ConfigurationException("accessKey, secretKey and bucket are required");
    }

    @Option(locations = Option.Location.Form, description = "The protocol to use when connecting to S3 (http or https)")
    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Option(locations = Option.Location.Form, description = "The host to use when connecting to S3")
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Option(locations = Option.Location.Form, description = "The port to use when connecting to S3")
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Option(locations = Option.Location.Form, required = true, description = "The S3 access key")
    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    @Option(locations = Option.Location.Form, required = true, description = "The secret key for the specified access key")
    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    @Option(description = "Specifies whether virtual hosted buckets will be disabled (and path-style buckets will be used)")
    public boolean isDisableVHosts() {
        return disableVHosts;
    }

    public void setDisableVHosts(boolean disableVHosts) {
        this.disableVHosts = disableVHosts;
    }

    @Option(locations = Option.Location.Form, required = true, description = "Specifies the bucket to use")
    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    @Option(description = "By default, the target bucket must exist. This option will create it if it does not")
    public boolean isCreateBucket() {
        return createBucket;
    }

    public void setCreateBucket(boolean createBucket) {
        this.createBucket = createBucket;
    }

    @Option(locations = Option.Location.Form, description = "")
    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Option(description = "Specifies if keys will be URL-decoded after listing them. This can fix problems if you see file or directory names with characters like %2f in them")
    public boolean isDecodeKeys() {
        return decodeKeys;
    }

    public void setDecodeKeys(boolean decodeKeys) {
        this.decodeKeys = decodeKeys;
    }

    @Option(description = "Transfer all versions of every object. NOTE: this will overwrite all versions of each source key in the target system if any exist!")
    public boolean isIncludeVersions() {
        return includeVersions;
    }

    public void setIncludeVersions(boolean includeVersions) {
        this.includeVersions = includeVersions;
    }

    @Option(description = "Specifies whether the client will use v2 auth. Necessary for ECS < 3.0")
    public boolean isLegacySignatures() {
        return legacySignatures;
    }

    public void setLegacySignatures(boolean legacySignatures) {
        this.legacySignatures = legacySignatures;
    }

    @Option(valueHint = "size-in-MB", description = "Sets the size threshold (in MB) when an upload shall become a multipart upload")
    public int getMpuThresholdMb() {
        return mpuThresholdMb;
    }

    public void setMpuThresholdMb(int mpuThresholdMb) {
        this.mpuThresholdMb = mpuThresholdMb;
    }

    @Option(valueHint = "size-in-MB", description = "Sets the part size to use when multipart upload is required (objects over 5GB). Default is " + DEFAULT_MPU_PART_SIZE_MB + "MB, minimum is " + MIN_PART_SIZE_MB + "MB")
    public int getMpuPartSizeMb() {
        return mpuPartSizeMb;
    }

    public void setMpuPartSizeMb(int mpuPartSizeMb) {
        this.mpuPartSizeMb = mpuPartSizeMb;
    }

    @Option(description = "The number of threads to use for multipart upload (only applicable for file sources)")
    public int getMpuThreadCount() {
        return mpuThreadCount;
    }

    public void setMpuThreadCount(int mpuThreadCount) {
        this.mpuThreadCount = mpuThreadCount;
    }

    @Option(valueHint = "timeout-ms", description = "Sets the socket timeout in milliseconds (default is " + DEFAULT_SOCKET_TIMEOUT + "ms)")
    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    @Option(cliInverted = true, description = "By default, directories are stored in S3 as empty objects to preserve empty dirs and metadata from the source. Use this option to disable that behavior")
    public boolean isPreserveDirectories() {
        return preserveDirectories;
    }

    public void setPreserveDirectories(boolean preserveDirectories) {
        this.preserveDirectories = preserveDirectories;
    }
}
